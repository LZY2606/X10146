package walsandbox.sandbox;

import walsandbox.recover.Recovery;
import walsandbox.recover.RecoveryResult;
import walsandbox.util.Json;
import walsandbox.util.ZipBags;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Persistent collection of experiments under a root directory.
 *
 *   <root>/experiments.json         all experiment definitions + crash selection
 *   <root>/<id>/data/               materialized on-disk prefix (real raw bytes)
 *   <root>/<id>/recovery.json       last recovery trace (if any)
 */
public final class SandboxStore {

    private final Path root;
    private final Map<String, Experiment> experiments = new LinkedHashMap<>();
    private int idCounter = 1;

    public SandboxStore(Path root) throws IOException {
        this.root = root;
        Files.createDirectories(root);
        load();
    }

    public Path root() {
        return root;
    }

    public Path dataDir(String id) {
        return root.resolve(id).resolve("data");
    }

    public List<Experiment> list() {
        return List.copyOf(experiments.values());
    }

    public Experiment get(String id) {
        Experiment e = experiments.get(id);
        if (e == null) {
            throw new IllegalArgumentException("实验不存在: " + id);
        }
        return e;
    }

    public synchronized Experiment create(String name) throws IOException {
        String id = "exp-" + (idCounter++);
        Experiment exp = new Experiment(id, name == null || name.isBlank()
                ? "未命名实验" : name);
        experiments.put(id, exp);
        Files.createDirectories(dataDir(id));
        save();
        return exp;
    }

    public synchronized void rename(String id, String name) throws IOException {
        get(id).setName(name);
        save();
    }

    public synchronized void delete(String id) throws IOException {
        experiments.remove(id);
        Path dir = root.resolve(id);
        if (Files.exists(dir)) {
            try (var walk = Files.walk(dir)) {
                List<Path> paths = new ArrayList<>();
                walk.sorted((a, b) -> b.compareTo(a)).forEach(paths::add);
                for (Path p : paths) {
                    Files.deleteIfExists(p);
                }
            }
        }
        save();
    }

    public synchronized int addEvent(String id, String type, String key, String value,
                                     Map<String, Object> faultBody) throws IOException {
        Experiment exp = get(id);
        int eventId = exp.addEvent(type, key, value, parseFault(faultBody));
        save();
        return eventId;
    }

    public synchronized void setFault(String id, int eventId,
                                      Map<String, Object> faultBody) throws IOException {
        get(id).setFault(eventId, parseFault(faultBody));
        save();
    }

    public synchronized void setCrash(String id, CrashSpec spec) throws IOException {
        get(id).setCrashSpec(spec);
        save();
    }

    public synchronized Materialization materialize(String id) throws IOException {
        Experiment exp = get(id);
        Path data = dataDir(id);
        Path recoveryFile = root.resolve(id).resolve("recovery.json");
        Files.deleteIfExists(recoveryFile);
        Materialization result = Materializer.materialize(data, exp.events(),
                exp.crashSpec());
        saveRecoverySummary(id, result, null);
        return result;
    }

    public synchronized RecoveryResult recover(String id) throws IOException {
        RecoveryResult result = Recovery.run(dataDir(id));
        saveRecovery(id, result);
        return result;
    }

    public synchronized Materialization resetPower(String id) throws IOException {
        Experiment exp = get(id);
        exp.setCrashSpec(CrashSpec.none());
        exp.clearFaults();
        save();
        Path recoveryFile = root.resolve(id).resolve("recovery.json");
        Files.deleteIfExists(recoveryFile);
        return Materializer.materialize(dataDir(id), exp.events(), CrashSpec.none());
    }

    public synchronized Path exportZip(String id) throws IOException {
        save();
        Experiment exp = get(id);
        Path expDir = root.resolve(id);
        Path meta = expDir.resolve("experiment.json");
        Files.writeString(meta, Json.pretty(serialize(exp)));
        Path out = expDir.resolve("export.zip");
        ZipBags.zipDirectory(expDir, out, "export.zip");
        return out;
    }

    public synchronized String importZip(Path zip) throws IOException {
        Path tmp = Files.createTempDirectory("wal-sandbox-import");
        ZipBags.unzip(zip, tmp);
        Path metaFile = tmp.resolve("experiment.json");
        if (!Files.isRegularFile(metaFile)) {
            throw new IOException("导出包缺少 experiment.json");
        }
        Map<String, Object> meta = Json.object(Files.readString(metaFile));
        Experiment exp = new Experiment("x", "");
        deserialize(exp, meta);
        String id = "exp-" + (idCounter++);
        Experiment renamed = new Experiment(id, exp.name());
        deserialize(renamed, meta);
        experiments.put(id, renamed);
        Path target = root.resolve(id);
        Files.createDirectories(target);
        Path dataSrc = tmp.resolve("data");
        if (Files.isDirectory(dataSrc)) {
            copyTree(dataSrc, target.resolve("data"));
        } else {
            Files.createDirectories(target.resolve("data"));
        }
        Path recSrc = tmp.resolve("recovery.json");
        if (Files.isRegularFile(recSrc)) {
            Files.copy(recSrc, target.resolve("recovery.json"));
        }
        save();
        return id;
    }

    // ---- persistence ----------------------------------------------------

    private void save() throws IOException {
        List<Object> list = new ArrayList<>();
        for (Experiment e : experiments.values()) {
            list.add(serialize(e));
        }
        Files.writeString(root.resolve("experiments.json"), Json.pretty(
                Map.of("idCounter", idCounter, "experiments", list)));
    }

    private void load() throws IOException {
        Path f = root.resolve("experiments.json");
        if (!Files.isRegularFile(f)) {
            return;
        }
        Map<String, Object> doc = Json.object(Files.readString(f));
        idCounter = Json.integer(doc, "idCounter", 1);
        for (Map<String, Object> body : Json.listOfObjects(doc, "experiments")) {
            String id = Json.str(body, "id");
            Experiment exp = new Experiment(id, Json.str(body, "name"));
            deserialize(exp, body);
            experiments.put(id, exp);
        }
    }

    private Map<String, Object> serialize(Experiment exp) {
        List<Object> eventList = new ArrayList<>();
        for (Event e : exp.events()) {
            eventList.add(serializeEvent(e));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", exp.id());
        m.put("name", exp.name());
        m.put("nextEventId", exp.nextEventId());
        m.put("nextTxnId", exp.nextTxnId());
        m.put("activeTxnId", exp.activeTxnId());
        m.put("events", eventList);
        Map<String, Object> crash = new LinkedHashMap<>();
        crash.put("mode", exp.crashSpec().mode().name());
        crash.put("value", exp.crashSpec().value());
        m.put("crash", crash);
        return m;
    }

    private void deserialize(Experiment exp, Map<String, Object> body) {
        exp.setName(Json.str(body, "name"));
        exp.restoreState(Json.integer(body, "nextEventId", 1),
                Json.longValue(body, "nextTxnId", 1),
                Json.longValue(body, "activeTxnId", 0));
        exp.events().clear();
        for (Map<String, Object> ev : Json.listOfObjects(body, "events")) {
            Map<String, Object> faultBody = ev.get("fault") instanceof Map<?, ?> fm
                    ? castMap(fm) : Map.of();
            exp.events().add(new Event(Json.integer(ev, "id", 0), Json.str(ev, "type"),
                    ev.get("txnId") == null ? null : Json.longValue(ev, "txnId", 0),
                    ev.get("seq") == null ? null : Json.longValue(ev, "seq", 0),
                    Json.str(ev, "key"), Json.str(ev, "value"), parseFault(faultBody)));
        }
        Object crashObj = body.get("crash");
        if (crashObj instanceof Map<?, ?> cm) {
            @SuppressWarnings("unchecked")
            Map<String, Object> crash = (Map<String, Object>) cm;
            exp.setCrashSpec(new CrashSpec(
                    CrashSpec.Mode.valueOf(Json.str(crash, "mode")),
                    Json.integer(crash, "value", 0)));
        }
    }

    private Map<String, Object> serializeEvent(Event e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.id());
        m.put("type", e.type());
        m.put("txnId", e.txnId());
        m.put("seq", e.seq());
        m.put("key", e.key());
        m.put("value", e.value());
        m.put("fault", serializeFault(e.fault()));
        return m;
    }

    private Map<String, Object> serializeFault(Fault f) {
        if (f == null || !f.active()) {
            return Map.of("kind", "NONE");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", f.kind().name());
        m.put("bytes", f.bytes());
        m.put("byteOffset", f.byteOffset());
        m.put("bitIndex", f.bitIndex());
        m.put("cpPhase", f.cpPhase());
        return m;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object o) {
        return (Map<String, Object>) o;
    }

    private Fault parseFault(Map<String, Object> body) {
        if (body == null) {
            return Fault.none();
        }
        String kind = Json.str(body, "kind");
        if (kind == null || kind.equals("NONE")) {
            return Fault.none();
        }
        return new Fault(Fault.Kind.valueOf(kind),
                Json.integer(body, "bytes", 0),
                Json.integer(body, "byteOffset", 0),
                Json.integer(body, "bitIndex", 0),
                Json.str(body, "cpPhase"));
    }

    private void saveRecovery(String id, RecoveryResult result) throws IOException {
        Path f = root.resolve(id).resolve("recovery.json");
        Files.writeString(f, Json.pretty(serializeRecovery(result)));
    }

    private void saveRecoverySummary(String id, Materialization mat, Object ignored)
            throws IOException {
        Path f = root.resolve(id).resolve("materialization.json");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("crashed", mat.crashed());
        m.put("crashReason", mat.crashReason());
        m.put("eventsApplied", mat.eventsApplied());
        m.put("durableBytes", mat.durableBytes());
        Files.writeString(f, Json.pretty(m));
    }

    public Map<String, Object> serializeRecovery(RecoveryResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("snapshotUsed", r.snapshotUsed());
        m.put("snapshotGeneration", r.snapshotGeneration());
        m.put("startSegmentId", r.startSegmentId());
        m.put("kv", new TreeMap<>(r.kv()));
        m.put("status", r.status().name());
        m.put("terminalReason", r.terminalReason());
        m.put("committedTxns", r.committedTxns());
        m.put("discardedTxns", r.discardedTxns());
        m.put("framesAccepted", r.framesAccepted());
        m.put("framesRejected", r.framesRejected());
        List<Object> steps = new ArrayList<>();
        for (var s : r.steps()) {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("index", s.index());
            sm.put("kind", s.kind());
            sm.put("segmentId", s.segmentId());
            sm.put("offset", s.offset());
            sm.put("title", s.title());
            sm.put("detail", s.detail());
            sm.put("kvAfter", s.kvAfter());
            steps.add(sm);
        }
        m.put("steps", steps);
        return m;
    }

    private static void copyTree(Path src, Path dst) throws IOException {
        Files.createDirectories(dst);
        try (var walk = Files.walk(src)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                Path rel = src.relativize(p);
                Path target = dst.resolve(rel.toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target);
                }
            }
        }
    }
}
