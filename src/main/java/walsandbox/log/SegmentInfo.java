package walsandbox.log;

import java.nio.file.Path;

public record SegmentInfo(long id, long prevId, Path path, long length) {

    public String fileName() {
        return path.getFileName().toString();
    }
}
