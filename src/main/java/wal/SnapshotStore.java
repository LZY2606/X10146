package wal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Persistent key/value snapshots written with the staged
 * write-temp → fsync-temp → atomic-replace → fsync-dir protocol.
 *
 * <p>A snapshot is only selectable when its file verifies as complete
 * (see {@link SnapshotFormat#decode}). A temp file left behind by a crash,
 * or a final file whose replacement was torn, never shadows the previous
 * generation: recovery therefore always sees exactly one whole snapshot,
 * either the old one or the new one.
 */
public final class SnapshotStore {

    public static final String SNAPSHOT_PREFIX = "snapshot-";
    public static final String SNAPSHOT_SUFFIX = ".snp";
    public static final String TEMP_SUFFIX = ".tmp";

    private SnapshotStore() {}

    public static String snapshotName(long snapshotId) {
        return String.format("%s%016d%s", SNAPSHOT_PREFIX, snapshotId, SNAPSHOT_SUFFIX);
    }

    public static String tempName(long snapshotId) {
        return snapshotName(snapshotId) + TEMP_SUFFIX;
    }

    /** Writes the complete snapshot through all durable phases. */
    public static Path writeSnapshot(Path dir, long snapshotId, long nextTxnId,
                                    int nextSegIndex, Map<String, String> kv)
            throws IOException {
        writeTemp(dir, snapshotId, nextTxnId, nextSegIndex, kv, -1, false);
        return replaceTemp(dir, snapshotId);
    }

    /**
     * Writes the temp file. {@code crashAfterBytes} >= 0 simulates a power loss
     * after that many durable bytes (without a final fsync ordering beyond each
     * write being flushed); {@code fsyncWhenDone} selects whether the temp is
     * synced before control returns.
     *
     * @return the temp path
     */
    public static Path writeTemp(Path dir, long snapshotId, long nextTxnId,
                                 int nextSegIndex, Map<String, String> kv,
                                 int crashAfterBytes, boolean fsyncWhenDone)
            throws IOException {
        Files.createDirectories(dir);
        Path temp = dir.resolve(tempName(snapshotId));
        byte[] data = SnapshotFormat.encode(snapshotId, nextTxnId, nextSegIndex, kv);
        try (FileChannel ch = FileChannel.open(temp,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            int len = data.length;
            if (crashAfterBytes >= 0) {
                len = Math.min(len, crashAfterBytes);
            }
            ByteBuffer buf = ByteBuffer.wrap(data, 0, len);
            while (buf.hasRemaining()) {
                ch.write(buf);
            }
            ch.force(true);
        }
        if (fsyncWhenDone) {
            fsyncFile(temp);
        }
        return temp;
    }

    /** Atomically replaces the final snapshot with the completed temp and syncs the dir. */
    public static Path replaceTemp(Path dir, long snapshotId) throws IOException {
        Path temp = dir.resolve(tempName(snapshotId));
        Path target = dir.resolve(snapshotName(snapshotId));
        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        fsyncDir(dir);
        return target;
    }

    public static void fsyncFile(Path file) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            ch.force(true);
        }
    }

    public static void fsyncDir(Path dir) throws IOException {
        try (FileChannel ch = FileChannel.open(dir, StandardOpenOption.READ)) {
            ch.force(true);
        } catch (IOException ignored) {
            // Some platforms disallow opening directories; the atomic move is still durable
            // once the containing directory journal commits.
        }
    }

    /** One candidate snapshot file observed on disk. */
    public static final class Candidate {
        public final Path path;
        public final long id;
        public final boolean complete;
        public final SnapshotFormat.Snapshot snapshot;

        Candidate(Path path, long id, boolean complete, SnapshotFormat.Snapshot snapshot) {
            this.path = path;
            this.id = id;
            this.complete = complete;
            this.snapshot = snapshot;
        }
    }

    /**
     * Inspects every snapshot-looking file (including .tmp and corrupt files).
     */
    public static List<Candidate> inspect(Path dir) throws IOException {
        List<Candidate> result = new ArrayList<>();
        Files.createDirectories(dir);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path file : stream) {
                if (!Files.isRegularFile(file)) continue;
                String name = file.getFileName().toString();
                if (!name.startsWith(SNAPSHOT_PREFIX)) continue;
                byte[] data = Files.readAllBytes(file);
                SnapshotFormat.Snapshot snap = SnapshotFormat.decode(data);
                boolean complete = snap != null;
                long id = complete ? snap.snapshotId : parseId(name);
                result.add(new Candidate(file, id, complete, snap));
            }
        }
        return result;
    }

    /**
     * Chooses the highest-generation complete snapshot. Incomplete files never win,
     * so after any checkpoint-phase crash the choice is the whole old snapshot or
     * the whole new snapshot.
     */
    public static SnapshotFormat.Snapshot selectLatest(Path dir) throws IOException {
        SnapshotFormat.Snapshot best = null;
        for (Candidate c : inspect(dir)) {
            if (c.complete && (best == null || c.snapshot.snapshotId > best.snapshotId)) {
                best = c.snapshot;
            }
        }
        return best;
    }

    private static long parseId(String name) {
        try {
            String digits = name.replaceAll("[^0-9]", "");
            if (digits.isEmpty()) return -1;
            return Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
