package wal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Physical write-ahead log: a sequence of segment files.
 *
 * <p>Each segment begins with a {@link SegmentHeader} recording its generation.
 * Every append is a single {@code write} followed by {@code force(true)}, so the
 * "durable prefix" after a simulated power loss is exactly the prefix already
 * flushed when the crash interrupts the stream.
 */
public final class Journal {

    public static final String SEGMENT_SUFFIX = ".seg";

    /** One segment found on disk, identified by the generation embedded in its header. */
    public static final class SegmentFile {
        public final Path path;
        public final int index;

        SegmentFile(Path path, int index) {
            this.path = path;
            this.index = index;
        }
    }

    private Journal() {}

    public static String segmentName(int index) {
        return String.format("segment-%08d%s", index, SEGMENT_SUFFIX);
    }

    /**
     * Lists recognisable segments in a directory.
     *
     * <p>A file whose header is truncated or has a bad magic is ignored here and
     * reported separately by recovery diagnostics; identification uses the header,
     * not the filename.
     */
    public static List<SegmentFile> listSegments(Path dir) throws IOException {
        List<SegmentFile> result = new ArrayList<>();
        Files.createDirectories(dir);
        try (var stream = Files.list(dir)) {
            List<Path> files = new ArrayList<>();
            stream.filter(Files::isRegularFile).forEach(files::add);
            for (Path file : files) {
                SegmentHeader header = readHeader(file);
                if (header != null) {
                    result.add(new SegmentFile(file, header.index()));
                }
            }
        }
        result.sort(Comparator.comparingInt(s -> s.index));
        return result;
    }

    /** Lists all regular files, including ones with unrecognised headers. */
    public static List<Path> listAllFiles(Path dir) throws IOException {
        List<Path> files = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile).forEach(files::add);
        }
        files.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return files;
    }

    public static SegmentHeader readHeader(Path file) throws IOException {
        if (Files.size(file) < SegmentHeader.SIZE) {
            return null;
        }
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer buf = ByteBuffer.allocate(Segment.SEGMENT_HEADER_SIZE);
            int read = ch.read(buf);
            if (read < SegmentHeader.SIZE) {
                return null;
            }
            return SegmentHeader.decode(buf.array());
        }
    }

    /** Opens or creates the segment for the given generation for appending. */
    public static Segment open(Path dir, int index) throws IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve(segmentName(index));
        boolean fresh = !Files.exists(file);
        FileChannel ch = FileChannel.open(file,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ);
        if (fresh) {
            ch.write(ByteBuffer.wrap(new SegmentHeader(index).encode()));
            ch.force(true);
        } else {
            ch.position(ch.size());
        }
        return new Segment(file, index, ch);
    }

    public static final class Segment implements AutoCloseable {
        public static final int SEGMENT_HEADER_SIZE = SegmentHeader.SIZE;

        private final Path path;
        private final int index;
        private final FileChannel channel;

        Segment(Path path, int index, FileChannel channel) {
            this.path = path;
            this.index = index;
            this.channel = channel;
        }

        public int index() { return index; }
        public Path path() { return path; }
        public long position() throws IOException { return channel.position(); }

        /**
         * Appends bytes durably. A shortWriteLimit >= 0 writes only that many bytes,
         * simulating a torn append; the bytes are still forced.
         *
         * @return number of bytes written
         */
        public int appendDurable(byte[] data, int shortWriteLimit) throws IOException {
            int len = data.length;
            if (shortWriteLimit >= 0) {
                len = Math.min(len, shortWriteLimit);
            }
            ByteBuffer buf = ByteBuffer.wrap(data, 0, len);
            while (buf.hasRemaining()) {
                channel.write(buf);
            }
            channel.force(true);
            return len;
        }

        public int appendDurable(byte[] data) throws IOException {
            return appendDurable(data, -1);
        }

        @Override
        public void close() throws IOException {
            channel.close();
        }
    }
}
