package walsandbox.log;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;

public final class IOUtils {

    private IOUtils() {
    }

    /** fsync a directory (best effort on platforms that cannot open directories). */
    public static void fsyncDir(Path dir) throws IOException {
        try (FileInputStream in = new FileInputStream(dir.toFile())) {
            in.getFD().sync();
        } catch (IOException | RuntimeException e) {
            // Directory fsync is unavailable on some platforms; durability is best effort.
        }
    }
}
