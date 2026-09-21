package walsandbox.log;

import walsandbox.frame.Frame;
import walsandbox.frame.FrameScanner;
import walsandbox.frame.FrameType;
import walsandbox.frame.ScanOutcome;
import walsandbox.frame.ScannedFrame;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Append-only collection of numbered log segments.
 *
 * Segment files are named {@code log-NNNNNN.seg} for human readability, but nothing
 * trusts those names: both discovery and chaining read the embedded SEGHDR frame
 * (segmentId, prevSegmentId) and verify its CRC.
 */
public final class SegmentLog implements AutoCloseable {

    public static final String PREFIX = "log-";
    public static final String SUFFIX = ".seg";
    public static final long FIRST_SEGMENT_ID = 1;

    private final Path dir;
    private FileChannel current;
    private long currentId;
    private long prevId;

    private SegmentLog(Path dir) {
        this.dir = dir;
    }

    public static String segmentFileName(long id) {
        return String.format(PREFIX + "%06d" + SUFFIX, id);
    }

    public static Path segmentPath(Path dir, long id) {
        return dir.resolve(segmentFileName(id));
    }

    /** Open the newest chained segment for appending, or create segment 1 fresh. */
    public static SegmentLog openOrCreate(Path dir) throws IOException {
        Files.createDirectories(dir);
        SegmentLog log = new SegmentLog(dir);
        Map<Long, SegmentInfo> segments = discover(dir);
        if (segments.isEmpty()) {
            log.startNew(FIRST_SEGMENT_ID, 0);
        } else {
            SegmentInfo newest = null;
            for (SegmentInfo info : segments.values()) {
                if (newest == null || info.id() > newest.id()) {
                    newest = info;
                }
            }
            log.currentId = newest.id();
            log.prevId = newest.prevId();
            log.current = FileChannel.open(newest.path(), StandardOpenOption.WRITE,
                    StandardOpenOption.READ);
            log.current.position(newest.length());
        }
        return log;
    }

    private void startNew(long id, long previousId) throws IOException {
        Path path = segmentPath(dir, id);
        if (Files.exists(path)) {
            throw new IOException("segment file already exists: " + path);
        }
        current = FileChannel.open(path, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, StandardOpenOption.READ);
        currentId = id;
        prevId = previousId;
        appendRaw(Frame.segHdr(id, previousId).encode());
        IOUtils.fsyncDir(dir);
    }

    /** Finish the current segment and start a new chained one. */
    public void rotate(long newSegmentId) throws IOException {
        long oldId = currentId;
        current.force(true);
        current.close();
        startNew(newSegmentId, oldId);
    }

    public long appendFrame(Frame frame) throws IOException {
        return appendRaw(frame.encode());
    }

    /** Append exact bytes and force them; returns the file length after the write. */
    public long appendRaw(byte[] data) throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(data);
        while (buf.hasRemaining()) {
            current.write(buf);
        }
        current.force(true);
        return current.position();
    }

    public long currentId() {
        return currentId;
    }

    public long prevId() {
        return prevId;
    }

    public long currentLength() throws IOException {
        return current.position();
    }

    public Path currentPath() {
        return segmentPath(dir, currentId);
    }

    public void deleteSegment(long id) throws IOException {
        Files.deleteIfExists(segmentPath(dir, id));
        IOUtils.fsyncDir(dir);
    }

    @Override
    public void close() throws IOException {
        if (current != null && current.isOpen()) {
            current.force(true);
            current.close();
        }
    }

    /**
     * Discover segments by identity embedded in SEGHDR, never by filename text.
     * Files whose first frame is not a valid SEGHDR (snapshots, temp files, garbage)
     * are ignored rather than guessed about.
     */
    public static Map<Long, SegmentInfo> discover(Path dir) throws IOException {
        TreeMap<Long, SegmentInfo> result = new TreeMap<>();
        if (!Files.isDirectory(dir)) {
            return result;
        }
        try (var stream = Files.list(dir)) {
            for (Path path : (Iterable<Path>) stream::iterator) {
                if (!Files.isRegularFile(path)) {
                    continue;
                }
                String name = path.getFileName().toString();
                if (name.equals(Snapshot.FILE_NAME) || name.endsWith(Snapshot.TMP_SUFFIX)) {
                    continue;
                }
                headerOf(path).ifPresent(info -> {
                    if (result.put(info.id(), info) != null) {
                        throw new IllegalStateException("duplicate embedded segment id "
                                + info.id());
                    }
                });
            }
        }
        return result;
    }

    private static Optional<SegmentInfo> headerOf(Path path) {
        try {
            ScanOutcome outcome = FrameScanner.scan(path, 0);
            if (outcome.frames().isEmpty()) {
                return Optional.empty();
            }
            ScannedFrame first = outcome.frames().get(0);
            if (first.frame().type() != FrameType.SEGHDR) {
                return Optional.empty();
            }
            long[] id = first.frame().segHdrPayload();
            return Optional.of(new SegmentInfo(id[0], id[1], path,
                    Files.size(path)));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }
}
