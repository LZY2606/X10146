package wal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Binary snapshot format.
 *
 * <pre>
 *   int   magic = 0x534E4131 ("SNA1")
 *   long  snapshotId        (monotonic checkpoint generation)
 *   long  nextTxnId
 *   int   nextSegIndex     (first segment needed when continuing replay)
 *   int   entryCount
 *   repeated: utf key, utf value
 *   long  snapshotId        (trailer, repeated)
 *   int   crc32             (CRC of everything from magic through the trailer id)
 * </pre>
 *
 * A snapshot is only considered complete when the trailer id matches the
 * header and the final checksum verifies. A file missing either (interrupted
 * temp-file write, interrupted replacement of the final file) is discarded.
 */
public final class SnapshotFormat {
    public static final int MAGIC = 0x534E4131;

    private SnapshotFormat() {}

    public static final class Snapshot {
        public final long snapshotId;
        public final long nextTxnId;
        public final int nextSegIndex;
        public final Map<String, String> kv;

        public Snapshot(long snapshotId, long nextTxnId, int nextSegIndex,
                        Map<String, String> kv) {
            this.snapshotId = snapshotId;
            this.nextTxnId = nextTxnId;
            this.nextSegIndex = nextSegIndex;
            this.kv = kv;
        }
    }

    public static byte[] encode(long snapshotId, long nextTxnId, int nextSegIndex,
                                Map<String, String> kv) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(body)) {
            out.writeInt(MAGIC);
            out.writeLong(snapshotId);
            out.writeLong(nextTxnId);
            out.writeInt(nextSegIndex);
            out.writeInt(kv.size());
            for (Map.Entry<String, String> e : kv.entrySet()) {
                writeUtf(out, e.getKey());
                writeUtf(out, e.getValue());
            }
            out.writeLong(snapshotId);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        byte[] bodyBytes = body.toByteArray();
        int crc = Crc32c.crc32(bodyBytes, 0, bodyBytes.length, new byte[0], 0, 0);
        ByteBuffer out = ByteBuffer.allocate(bodyBytes.length + 4);
        out.put(bodyBytes);
        out.putInt(crc);
        return out.array();
    }

    /** Returns null for any incomplete or corrupt snapshot. */
    public static Snapshot decode(byte[] data) {
        if (data == null || data.length < 4 + 8 + 8 + 4 + 4 + 8 + 4) {
            return null;
        }
        int crcStored = ByteBuffer.wrap(data, data.length - 4, 4).getInt();
        int crcComputed = Crc32c.crc32(data, 0, data.length - 4, new byte[0], 0, 0);
        if (crcStored != crcComputed) {
            return null;
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(
                data, 0, data.length - 4))) {
            if (in.readInt() != MAGIC) {
                return null;
            }
            long snapshotId = in.readLong();
            long nextTxnId = in.readLong();
            int nextSegIndex = in.readInt();
            int entries = in.readInt();
            if (entries < 0 || entries > 1_000_000) {
                return null;
            }
            Map<String, String> kv = new LinkedHashMap<>();
            for (int i = 0; i < entries; i++) {
                String key = readUtf(in);
                String value = readUtf(in);
                if (key == null || value == null || key.isEmpty()) {
                    return null;
                }
                kv.put(key, value);
            }
            if (in.readLong() != snapshotId) {
                return null;
            }
            return new Snapshot(snapshotId, nextTxnId, nextSegIndex, kv);
        } catch (IOException e) {
            return null;
        }
    }

    private static void writeUtf(DataOutputStream out, String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readUtf(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 0 || len > 64 * 1024 * 1024 || len > in.available()) {
            return null;
        }
        byte[] bytes = new byte[len];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
