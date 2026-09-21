package wal;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Persists every experiment (script JSON) and its materialised raw bytes
 * (segments + snapshots) so the exact recovery trace can be reproduced,
 * including on a brand new empty instance via zip export/import.
 */
public final class ExperimentStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type LIST_TYPE = new TypeToken<List<Experiment>>() {}.getType();

    private final Path root;
    private final Path experimentsDir;
    private final Path mediaRoot;
    private final Map<String, Experiment> cache = new LinkedHashMap<>();

    public ExperimentStore(Path root) {
        this.root = root;
        this.experimentsDir = root.resolve("experiments");
        this.mediaRoot = root.resolve("media");
        try {
            Files.createDirectories(experimentsDir);
            Files.createDirectories(mediaRoot);
            loadAll();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Path root() {
        return root;
    }

    private void loadAll() throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.list(experimentsDir)) {
            s.filter(p -> p.toString().endsWith(".json")).forEach(files::add);
        }
        files.sort(Comparator.comparing(p -> p.getFileName().toString()));
        for (Path f : files) {
            Experiment exp = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8),
                    Experiment.class);
            if (exp != null && exp.id != null) {
                cache.put(exp.id, exp);
            }
        }
    }

    public synchronized List<Experiment> list() {
        return new ArrayList<>(cache.values());
    }

    public synchronized Experiment get(String id) {
        return cache.get(id);
    }

    public synchronized Experiment create(String name) {
        Experiment exp = new Experiment();
        exp.id = "exp-" + Long.toString(System.currentTimeMillis(), 36)
                + "-" + Integer.toHexString((int) (Math.random() * 0xFFFF));
        exp.name = name == null || name.isBlank() ? "未命名实验" : name;
        exp.createdAt = System.currentTimeMillis();
        cache.put(exp.id, exp);
        save(exp);
        return exp;
    }

    public synchronized void save(Experiment exp) {
        try {
            Path f = experimentsDir.resolve(exp.id + ".json");
            Files.writeString(f, GSON.toJson(exp), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized void delete(String id) throws IOException {
        cache.remove(id);
        Files.deleteIfExists(experimentsDir.resolve(id + ".json"));
        Path media = mediaRoot.resolve(id);
        if (Files.exists(media)) {
            deleteRecursively(media);
        }
    }

    public Path mediaDir(String id) {
        return mediaRoot.resolve(id);
    }

    /** Materialises the experiment script/plan into raw bytes and returns the result. */
    public synchronized SimulationResult materialise(String id) throws IOException {
        Experiment exp = require(id);
        Path media = mediaDir(id);
        deleteRecursively(media);
        Files.createDirectories(media);
        SimulationResult result = new Simulator(media, exp.plan).run(exp.script);
        exp.materialisedRevision = exp.revision;
        save(exp);
        return result;
    }

    public synchronized RecoveryReport recover(String id) throws IOException {
        Experiment exp = require(id);
        Path media = mediaDir(id);
        Files.createDirectories(media);
        return new RecoveryEngine(media).recover();
    }

    private Experiment require(String id) {
        Experiment exp = cache.get(id);
        if (exp == null) {
            throw new IllegalArgumentException("实验不存在: " + id);
        }
        return exp;
    }

    /** Raw bytes actually on disk (segments + snapshots), for hex display/export. */
    public synchronized Map<String, byte[]> rawFiles(String id) throws IOException {
        Path media = mediaDir(id);
        Map<String, byte[]> files = new LinkedHashMap<>();
        if (!Files.exists(media)) {
            return files;
        }
        List<Path> paths = new ArrayList<>();
        try (Stream<Path> s = Files.list(media)) {
            s.filter(Files::isRegularFile).forEach(paths::add);
        }
        paths.sort(Comparator.comparing(p -> p.getFileName().toString()));
        for (Path p : paths) {
            files.put(p.getFileName().toString(), Files.readAllBytes(p));
        }
        return files;
    }

    /** Exports script + raw bytes; importing into an empty instance reproduces recovery. */
    public synchronized void exportZip(String id, OutputStream out) throws IOException {
        Experiment exp = require(id);
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry("experiment.json"));
            zip.write(GSON.toJson(exp).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            Path media = mediaDir(id);
            if (Files.exists(media)) {
                try (Stream<Path> s = Files.walk(media)) {
                    List<Path> paths = s.filter(Files::isRegularFile)
                            .sorted(Comparator.comparing(p -> p.toString())).toList();
                    for (Path p : paths) {
                        String rel = media.relativize(p).toString();
                        zip.putNextEntry(new ZipEntry("media/" + rel));
                        Files.copy(p, zip);
                        zip.closeEntry();
                    }
                }
            }
        }
    }

    /** Imports an exported zip as a new experiment with its bytes restored. */
    public synchronized Experiment importZip(InputStream in) throws IOException {
        Experiment exp = null;
        List<String[]> mediaEntries = new ArrayList<>();
        Path staging = Files.createTempDirectory("wal-import-");
        try (ZipInputStream zip = new ZipInputStream(in, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                Path target = safeResolve(staging, name);
                Files.createDirectories(target.getParent());
                Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
                if (name.equals("experiment.json")) {
                    exp = GSON.fromJson(Files.readString(target, StandardCharsets.UTF_8),
                            Experiment.class);
                }
            }
        }
        if (exp == null) {
            deleteRecursively(staging);
            throw new IOException("zip 中缺少 experiment.json");
        }
        exp.id = "exp-" + Long.toString(System.currentTimeMillis(), 36)
                + "-" + Integer.toHexString((int) (Math.random() * 0xFFFF));
        exp.name = exp.name + "（导入）";
        cache.put(exp.id, exp);
        save(exp);
        Path media = mediaDir(exp.id);
        deleteRecursively(media);
        Files.createDirectories(media);
        Path stagedMedia = staging.resolve("media");
        if (Files.exists(stagedMedia)) {
            copyRecursively(stagedMedia, media);
        }
        deleteRecursively(staging);
        return exp;
    }

    private static Path safeResolve(Path base, String name) throws IOException {
        Path resolved = base.resolve(name).normalize();
        if (!resolved.startsWith(base)) {
            throw new IOException("非法 zip 条目: " + name);
        }
        return resolved;
    }

    static void copyRecursively(Path src, Path dst) throws IOException {
        Files.walk(src).forEach(p -> {
            try {
                Path target = dst.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (Stream<Path> s = Files.walk(path)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }
}
