package walsandbox.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class ZipBags {

    private ZipBags() {
    }

    public static void zipDirectory(Path sourceDir, Path zipFile, String... excludeNames)
            throws IOException {
        List<String> excludes = List.of(excludeNames);
        Files.deleteIfExists(zipFile);
        try (OutputStream os = Files.newOutputStream(zipFile);
                ZipOutputStream zos = new ZipOutputStream(os)) {
            try (var walk = Files.walk(sourceDir)) {
                for (Path p : (Iterable<Path>) walk::iterator) {
                    String name = p.getFileName().toString();
                    if (p.equals(sourceDir) || excludes.contains(name)) {
                        continue;
                    }
                    String entry = sourceDir.relativize(p).toString();
                    if (Files.isDirectory(p)) {
                        zos.putNextEntry(new ZipEntry(entry + "/"));
                        zos.closeEntry();
                    } else {
                        zos.putNextEntry(new ZipEntry(entry));
                        Files.copy(p, zos);
                        zos.closeEntry();
                    }
                }
            }
        }
    }

    public static void unzip(Path zipFile, Path targetDir) throws IOException {
        Files.createDirectories(targetDir);
        try (InputStream is = Files.newInputStream(zipFile);
                ZipInputStream zis = new ZipInputStream(is)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path resolved = targetDir.resolve(entry.getName()).normalize();
                if (!resolved.startsWith(targetDir.normalize())) {
                    throw new IOException("zip slip: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(resolved);
                } else {
                    Files.createDirectories(resolved.getParent());
                    Files.copy(zis, resolved, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        }
    }
}
