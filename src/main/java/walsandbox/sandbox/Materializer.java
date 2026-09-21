package walsandbox.sandbox;

import walsandbox.frame.Frame;
import walsandbox.log.CrashException;
import walsandbox.log.IOUtils;
import walsandbox.log.SegmentLog;
import walsandbox.log.Snapshot;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * Rebuilds the on-disk data directory from an event log. This is the deterministic
 * "what would power loss leave behind" engine: a clean materialization writes every
 * frame; a crashed materialization stops at the durable prefix and optionally applies
 * one per-write fault. Rebuilding again from the same events yields identical bytes,
 * which is what makes experiments exportable and resettable.
 */
public final class Materializer {

    private final Path dir;
    private final List<Event> events;
    private final CrashSpec crash;
    private final List<MaterializationEvent> writes = new ArrayList<>();

    private FileChannel channel;
    private long segmentId;
    private long totalBytes;
    private long budget = Long.MAX_VALUE;
    private boolean stopped;
    private String crashReason = "";

    private long checkpointCount;
    private final TreeMap<String, String> liveKv = new TreeMap<>();
    private final Map<Long, Map<String, String>> txnPuts = new LinkedHashMap<>();
    private final Map<Long, List<String>> txnDeletes = new LinkedHashMap<>();

    private Materializer(Path dir, List<Event> events, CrashSpec crash) {
        this.dir = dir;
        this.events = events;
        this.crash = crash;
    }

    public static Materialization materialize(Path dir, List<Event> events, CrashSpec crash)
            throws IOException {
        return new Materializer(dir, events, crash).run();
    }

    private Materialization run() throws IOException {
        wipe(dir);
        Files.createDirectories(dir);
        if (crash.mode() == CrashSpec.Mode.AT_BYTE) {
            budget = Math.max(0, crash.value());
        }
        startSegment(SegmentLog.FIRST_SEGMENT_ID, 0);

        int applied = 0;
        for (Event event : events) {
            if (stopped) {
                break;
            }
            if (!process(event)) {
                break;
            }
            applied++;
            if (crash.mode() == CrashSpec.Mode.AFTER_EVENT
                    && crash.value() == event.id() && faultAware(event)) {
                stop("在事件 #" + event.id() + " 完成后模拟掉电");
            }
        }
        if (channel != null && channel.isOpen()) {
            channel.force(true);
            channel.close();
        }
        boolean crashed = stopped;
        if (!crashed && crash.mode() != CrashSpec.Mode.NONE) {
            crashed = true;
            crashReason = "崩溃点在最后一条记录之后：全部写入恰好完整落盘";
        }
        return new Materialization(crashed, crashReason, applied, totalBytes,
                List.copyOf(writes));
    }

    /** Faults are ignored unless a crash is selected; true means a fault was present. */
    private boolean faultAware(Event event) {
        return event.fault() != null && event.fault().active();
    }

    private boolean process(Event event) throws IOException {
        long txn = event.txnId() == null ? 0 : event.txnId();
        long seq = event.seq() == null ? 0 : event.seq();
        return switch (event.type()) {
            case "BEGIN" -> {
                txnPuts.put(txn, new LinkedHashMap<>());
                txnDeletes.put(txn, new ArrayList<>());
                yield writeEvent(event, Frame.begin(txn, seq));
            }
            case "PUT" -> {
                txnPuts.getOrDefault(txn, new LinkedHashMap<>())
                        .put(event.key(), event.value());
                yield writeEvent(event, Frame.put(txn, seq, event.key(), event.value()));
            }
            case "DELETE" -> {
                txnPuts.getOrDefault(txn, new LinkedHashMap<>()).remove(event.key());
                txnDeletes.computeIfAbsent(txn, k -> new ArrayList<>()).add(event.key());
                yield writeEvent(event, Frame.delete(txn, seq, event.key()));
            }
            case "COMMIT" -> {
                boolean ok = writeEvent(event, Frame.commit(txn, seq));
                if (ok) {
                    liveKv.putAll(txnPuts.getOrDefault(txn, Map.of()));
                    txnDeletes.getOrDefault(txn, List.of()).forEach(liveKv::remove);
                }
                yield ok;
            }
            case "CHECKPOINT" -> checkpoint(event);
            default -> throw new IllegalArgumentException("unknown event type "
                    + event.type());
        };
    }

    private boolean checkpoint(Event event) throws IOException {
        long generation = ++checkpointCount;
        long newSegmentId = generation + 1;

        channel.force(true);
        channel.close();
        startSegment(newSegmentId, segmentId);

        Frame cpFrame = Frame.checkpoint(generation, newSegmentId);
        if (!writeRaw(event.id(), cpFrame.encode(), Fault.none(), "CHECKPOINT 帧")) {
            return false;
        }

        Snapshot.CrashPhase phase = Snapshot.CrashPhase.NONE;
        String faultText = "无故障";
        Fault fault = event.fault();
        if (crash.mode() != CrashSpec.Mode.NONE && fault != null
                && fault.kind() == Fault.Kind.CP_INTERRUPT) {
            phase = switch (fault.cpPhase() == null ? "" : fault.cpPhase()) {
                case "TEMP_CREATED" -> Snapshot.CrashPhase.TEMP_CREATED;
                case "RENAMED" -> Snapshot.CrashPhase.RENAMED;
                default -> Snapshot.CrashPhase.TEMP_SYNCED;
            };
            faultText = "checkpoint 中断于 " + phase;
        }
        long beforeTmp = Files.exists(Snapshot.tmpFile(dir))
                ? Files.size(Snapshot.tmpFile(dir)) : 0;
        int snapLen = Snapshot.encode(generation, newSegmentId, liveKv).length;
        try {
            Snapshot.write(dir, generation, newSegmentId, liveKv, phase);
            writes.add(new MaterializationEvent(event.id(), 0, 0, snapLen, snapLen,
                    faultText));
        } catch (CrashException e) {
            writes.add(new MaterializationEvent(event.id(), 0, 0, snapLen,
                    (int) beforeTmp, faultText + " -> " + e.getMessage()));
            stop(e.getMessage());
            return false;
        }
        if (crash.mode() == CrashSpec.Mode.AFTER_EVENT
                && crash.value() == event.id()
                && event.fault() != null && event.fault().active()) {
            stop("在 CHECKPOINT #" + event.id() + " 完成后模拟掉电");
            return false;
        }
        if (crash.mode() == CrashSpec.Mode.AT_BYTE && budget <= 0) {
            stop("字节崩溃点落在 checkpoint 边界");
            return false;
        }

        for (long oldId = SegmentLog.FIRST_SEGMENT_ID; oldId < newSegmentId; oldId++) {
            Files.deleteIfExists(SegmentLog.segmentPath(dir, oldId));
        }
        IOUtils.fsyncDir(dir);
        return true;
    }

    private boolean writeEvent(Event event, Frame frame) throws IOException {
        Fault fault = crash.mode() == CrashSpec.Mode.NONE ? Fault.none()
                : (event.fault() == null ? Fault.none() : event.fault());
        return writeRaw(event.id(), frame.encode(), fault, frame.describe());
    }

    private boolean writeRaw(int eventId, byte[] raw, Fault fault, String description)
            throws IOException {
        byte[] data = raw;
        int fullLength = raw.length;
        int written = fullLength;
        String faultText = "无故障";

        if (fault.kind() == Fault.Kind.BIT_FLIP) {
            data = raw.clone();
            int pos = Math.floorMod(fault.byteOffset(), data.length);
            int bit = fault.bitIndex() & 7;
            data[pos] ^= (byte) (1 << bit);
            faultText = "位翻转：帧内偏移 " + pos + " 的第 " + bit + " 位";
        }

        if (fault.kind() == Fault.Kind.SHORT_WRITE) {
            int cut = Math.min(Math.max(0, fault.bytes()), data.length);
            written = data.length - cut;
            faultText = "短写：仅落盘 " + written + "/" + data.length + " 字节";
        }

        long allowed = Math.min(written, budget);
        long offset = channel.position();
        ByteBuffer out = ByteBuffer.wrap(data, 0, (int) allowed);
        while (out.hasRemaining()) {
            channel.write(out);
        }
        channel.force(true);
        totalBytes += allowed;
        budget -= allowed;
        writes.add(new MaterializationEvent(eventId, segmentId, offset, fullLength,
                (int) allowed, faultText));

        if (allowed < written) {
            stop("字节崩溃点落在本帧写入过程中：应写 " + written + " 字节，实际只有 "
                    + allowed + " 字节（" + description + "）");
            return false;
        }
        if (fault.kind() == Fault.Kind.SHORT_WRITE && written < fullLength) {
            stop("短写导致帧不完整：" + faultText + "（" + description + "）");
            return false;
        }

        if (fault.kind() == Fault.Kind.GARBAGE && fault.bytes() > 0) {
            byte[] garbage = new byte[fault.bytes()];
            new Random(0xC0FFEEL + eventId).nextBytes(garbage);
            long gAllowed = Math.min(garbage.length, budget);
            ByteBuffer gb = ByteBuffer.wrap(garbage, 0, (int) gAllowed);
            while (gb.hasRemaining()) {
                channel.write(gb);
            }
            channel.force(true);
            totalBytes += gAllowed;
            budget -= gAllowed;
            writes.add(new MaterializationEvent(-eventId, segmentId,
                    offset + fullLength, garbage.length, (int) gAllowed,
                    "尾部垃圾：追加 " + gAllowed + "/" + garbage.length + " 随机字节"));
            if (gAllowed < garbage.length) {
                stop("字节崩溃点落在尾部垃圾写入过程中");
                return false;
            }
        }
        return true;
    }

    private void startSegment(long id, long previousId) throws IOException {
        Path path = SegmentLog.segmentPath(dir, id);
        channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, StandardOpenOption.READ);
        segmentId = id;
        byte[] hdr = Frame.segHdr(id, previousId).encode();
        ByteBuffer out = ByteBuffer.wrap(hdr);
        long allowed = Math.min(hdr.length, budget);
        while (out.hasRemaining()) {
            channel.write(out);
        }
        channel.force(true);
        totalBytes += allowed;
        budget -= allowed;
        IOUtils.fsyncDir(dir);
        if (allowed < hdr.length) {
            stop("字节崩溃点落在 SEGHDR 段头写入过程中");
        }
    }

    private void stop(String reason) {
        stopped = true;
        crashReason = reason;
    }

    private static void wipe(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            List<Path> paths = new ArrayList<>();
            walk.sorted((a, b) -> b.compareTo(a)).forEach(paths::add);
            for (Path p : paths) {
                Files.deleteIfExists(p);
            }
        }
    }
}
