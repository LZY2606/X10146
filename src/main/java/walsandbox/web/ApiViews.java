package walsandbox.web;

import walsandbox.frame.Frame;
import walsandbox.recover.EvidenceStep;
import walsandbox.recover.RecoveryResult;
import walsandbox.sandbox.Event;
import walsandbox.sandbox.Experiment;
import walsandbox.sandbox.Fault;
import walsandbox.sandbox.Materialization;
import walsandbox.sandbox.MaterializationEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Builds JSON-friendly API views, including expected state and frame hex preview. */
public final class ApiViews {

    private ApiViews() {
    }

    public static Map<String, Object> summary(Experiment exp, Materialization materialization,
                                              boolean recovered) {
        Map<String, Object> m = base(exp);
        m.put("crash", Map.of("mode", exp.crashSpec().mode().name(),
                "value", exp.crashSpec().value()));
        m.put("materialized", materialization == null ? null
                : materializationView(materialization));
        m.put("recovered", recovered);
        return m;
    }

    public static Map<String, Object> base(Experiment exp) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", exp.id());
        m.put("name", exp.name());
        m.put("activeTxnId", exp.activeTxnId());
        m.put("transactionOpen", exp.transactionOpen());
        List<Object> events = new ArrayList<>();
        for (Event e : exp.events()) {
            events.add(eventView(e));
        }
        m.put("events", events);
        m.put("expectedKv", expectedKv(exp));
        return m;
    }

    public static Map<String, Object> eventView(Event e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.id());
        m.put("type", e.type());
        m.put("txnId", e.txnId());
        m.put("seq", e.seq());
        m.put("key", e.key());
        m.put("value", e.value());
        m.put("fault", faultView(e.fault()));
        byte[] raw = encode(e);
        m.put("frameLength", raw.length);
        m.put("hex", hexPreview(raw));
        m.put("description", describe(e));
        return m;
    }

    public static Map<String, Object> faultView(Fault f) {
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

    public static Map<String, Object> materializationView(Materialization mat) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("crashed", mat.crashed());
        m.put("crashReason", mat.crashReason());
        m.put("eventsApplied", mat.eventsApplied());
        m.put("durableBytes", mat.durableBytes());
        List<Object> writes = new ArrayList<>();
        for (MaterializationEvent w : mat.writes()) {
            Map<String, Object> wm = new LinkedHashMap<>();
            wm.put("eventId", w.eventId());
            wm.put("segmentId", w.segmentId());
            wm.put("fileOffset", w.fileOffset());
            wm.put("fullLength", w.fullLength());
            wm.put("writtenLength", w.writtenLength());
            wm.put("faultDescription", w.faultDescription());
            writes.add(wm);
        }
        m.put("writes", writes);
        return m;
    }

    public static Map<String, Object> recoveryView(RecoveryResult r) {
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
        for (EvidenceStep s : r.steps()) {
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

    /** Logical expectation if every appended record had survived (no crash). */
    public static TreeMap<String, String> expectedKv(Experiment exp) {
        TreeMap<String, String> kv = new TreeMap<>();
        Map<Long, TreeMap<String, String>> puts = new LinkedHashMap<>();
        Map<Long, List<String>> deletes = new LinkedHashMap<>();
        long current = 0;
        for (Event e : exp.events()) {
            switch (e.type()) {
                case "BEGIN" -> {
                    current = e.txnId();
                    puts.put(current, new TreeMap<>());
                    deletes.put(current, new ArrayList<>());
                }
                case "PUT" -> {
                    puts.computeIfAbsent(e.txnId(), k -> new TreeMap<>())
                            .put(e.key(), e.value());
                    deletes.getOrDefault(e.txnId(), List.of()).remove(e.key());
                }
                case "DELETE" -> {
                    puts.computeIfPresent(e.txnId(), (k, v) -> {
                        v.remove(e.key());
                        return v;
                    });
                    deletes.computeIfAbsent(e.txnId(), k -> new ArrayList<>()).add(e.key());
                }
                case "COMMIT" -> {
                    TreeMap<String, String> pending = puts.get(e.txnId());
                    if (pending != null) {
                        kv.putAll(pending);
                    }
                    deletes.getOrDefault(e.txnId(), List.of()).forEach(kv::remove);
                }
                default -> {
                }
            }
        }
        return kv;
    }

    private static byte[] encode(Event e) {
        long txn = e.txnId() == null ? 0 : e.txnId();
        long seq = e.seq() == null ? 0 : e.seq();
        return switch (e.type()) {
            case "BEGIN" -> Frame.begin(txn, seq).encode();
            case "PUT" -> Frame.put(txn, seq, e.key(), e.value() == null ? "" : e.value())
                    .encode();
            case "DELETE" -> Frame.delete(txn, seq, e.key()).encode();
            case "COMMIT" -> Frame.commit(txn, seq).encode();
            case "CHECKPOINT" -> Frame.checkpoint(0, 0).encode();
            default -> new byte[0];
        };
    }

    private static String describe(Event e) {
        long txn = e.txnId() == null ? 0 : e.txnId();
        long seq = e.seq() == null ? 0 : e.seq();
        return switch (e.type()) {
            case "BEGIN" -> "BEGIN txn=" + txn + " seq=" + seq;
            case "PUT" -> "PUT txn=" + txn + " seq=" + seq + " " + e.key() + "="
                    + e.value();
            case "DELETE" -> "DELETE txn=" + txn + " seq=" + seq + " " + e.key();
            case "COMMIT" -> "COMMIT txn=" + txn + " seq=" + seq;
            case "CHECKPOINT" -> "CHECKPOINT";
            default -> e.type();
        };
    }

    private static String hexPreview(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < data.length; i++) {
            sb.append(String.format("%02x", data[i] & 0xFF));
            if (i + 1 < data.length) {
                sb.append(' ');
            }
            if (i == Frame.HEADER_SIZE - 1) {
                sb.append("| ");
            }
        }
        return sb.toString();
    }
}
