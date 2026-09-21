package walsandbox.log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.zip.CRC32;

/**
 * Checkpoint snapshot. Durability follows the temp-file -> fsync -> atomic rename model.
 * Recovery ever reads only {@link #FILE_NAME}; {@code *.tmp} files are never a snapshot,
 * so a crash in any phase leaves either the previous complete snapshot or the new one.
 *
 * File format (big endian):
 *   8B magic "WALSNAP1", u32 version=1, u64 generation, u64 startSegmentId,
 *   u32 entryCount, per entry (u32 keyLen, key, u32 valLen, val),
 *   u32 complete marker 0x534E4150, u32 crc32 of all preceding bytes
 */
public final class Snapshot {

    public static final String FILE_NAME = "snapshot.dat";
    public static final String TMP_SUFFIX = ".tmp";
    public static final byte[] MAGIC = "WALSNAP1".getBytes(StandardCharsets.US_ASCII);
    public static final int VERSION = 1;
    public static final int COMPLETE_MARKER = 0x534E4150;

    public record Contents(long generation, long startSegmentId, TreeMap<String, String> kv) {
    }

    /** Crash injection point for the checkpoint durability protocol. */
    public enum CrashPhase {
        NONE,
        TEMP_CREATED,   // temp file exists with contents, never fsynced
        TEMP_SYNCED,    // temp file fsynced, rename not done
        RENAMED         // rename done, directory fsync not done
    }

    private Snapshot() {
    }

    public static Path file(Path dir) {
        return dir.resolve(FILE_NAME);
    }

    public static Path tmpFile(Path dir) {
        return dir.resolve(FILE_NAME + TMP_SUFFIX);
    }

    public static byte[] encode(long generation, long startSegmentId,
                                Map<String, String> kv) {
        Map<String, String> ordered = new TreeMap<>(kv);
        int entryBytes = 0;
        for (Map.Entry<String, String> e : ordered.entrySet()) {
            entryBytes += 8 + utf8(e.getKey()).length + utf8(e.getValue()).length;
        }
        int bodyLen = MAGIC.length + 4 + 16 + 4 + entryBytes;
        ByteBuffer buf = ByteBuffer.allocate(bodyLen + 8).order(ByteOrder.BIG_ENDIAN);
        buf.put(MAGIC);
        buf.putInt(VERSION);
        buf.putLong(generation);
        buf.putLong(startSegmentId);
        buf.putInt(ordered.size());
        for (Map.Entry<String, String> e : ordered.entrySet()) {
            putStr(buf, e.getKey());
            putStr(buf, e.getValue());
        }
        buf.putInt(COMPLETE_MARKER);
        CRC32 crc = new CRC32();
        crc.update(buf.array(), 0, bodyLen + 4);
        buf.putInt((int) crc.getValue());
        return buf.array();
    }

    public static Optional<Contents> read(Path dir) {
        Path f = file(dir);
        if (!Files.isRegularFile(f)) {
            return Optional.empty();
        }
        try {
            return Optional.of(decode(Files.readAllBytes(f)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public static Contents decode(byte[] data) {
        if (data.length < MAGIC.length + 4 + 16 + 4 + 8) {
            throw new IllegalArgumentException("snapshot too short");
        }
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        byte[] magic = new byte[MAGIC.length];
        buf.get(magic);
        if (!java.util.Arrays.equals(magic, MAGIC)) {
            throw new IllegalArgumentException("bad magic");
        }
        int version = buf.getInt();
        if (version != VERSION) {
            throw new IllegalArgumentException("unsupported version " + version);
        }
        long generation = buf.getLong();
        long startSegmentId = buf.getLong();
        int count = buf.getInt();
        TreeMap<String, String> kv = new TreeMap<>();
        for (int i = 0; i < count; i++) {
            String key = getStr(buf);
            String value = getStr(buf);
            kv.put(key, value);
        }
        if (buf.remaining() != 8) {
            throw new IllegalArgumentException("trailing bytes mismatch");
        }
        int marker = buf.getInt();
        if (marker != COMPLETE_MARKER) {
            throw new IllegalArgumentException("snapshot not marked complete");
        }
        int storedCrc = buf.getInt();
        CRC32 crc = new CRC32();
        crc.update(data, 0, data.length - 4);
        if (storedCrc != (int) crc.getValue()) {
            throw new IllegalArgumentException("snapshot crc mismatch");
        }
        return new Contents(generation, startSegmentId, kv);
    }

    /**
     * Run the checkpoint durability protocol. When {@code phase} is not NONE the method
     * simulates power loss at the given phase by throwing {@link CrashException}, leaving
     * exactly the bytes that would have survived.
     */
    public static void write(Path dir, long generation, long startSegmentId,
                             Map<String, String> kv, CrashPhase phase) throws IOException {
        Files.createDirectories(dir);
        Path target = file(dir);
        Path tmp = tmpFile(dir);
        byte[] encoded = encode(generation, startSegmentId, kv);

        Files.deleteIfExists(tmp);
        try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(tmp,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer src = ByteBuffer.wrap(encoded);
            while (src.hasRemaining()) {
                ch.write(src);
            }
            if (phase == CrashPhase.TEMP_CREATED) {
                throw new CrashException("掉电：临时快照已创建但尚未同步");
            }
            ch.force(true);
        }
        if (phase == CrashPhase.TEMP_SYNCED) {
            throw new CrashException("掉电：临时快照已同步但尚未替换");
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        if (phase == CrashPhase.RENAMED) {
            throw new CrashException("掉电：快照已替换但目录尚未同步");
        }
        IOUtils.fsyncDir(dir);
    }

    public static void writeComplete(Path dir, long generation, long startSegmentId,
                                     Map<String, String> kv) throws IOException {
        write(dir, generation, startSegmentId, kv, CrashPhase.NONE);
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static void putStr(ByteBuffer buf, String s) {
        byte[] b = utf8(s);
        buf.putInt(b.length);
        buf.put(b);
    }

    private static String getStr(ByteBuffer buf) {
        int len = buf.getInt();
        byte[] b = new byte[len];
        buf.get(b);
        return new String(b, StandardCharsets.UTF_8);
    }
}
