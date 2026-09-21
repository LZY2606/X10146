package wal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes an experiment script into a fresh directory, injects at most one
 * write fault, and applies the chosen power-loss truncation.
 *
 * <p>Every successful write is immediately forced to disk, so the files left
 * behind are exactly the durable prefix a real crash would expose.
 */
public final class Simulator {

    private final Path dir;
    private final CrashPlan plan;

    private final Map<String, Long> txnIds = new LinkedHashMap<>();
    private final Map<Long, Integer> seq = new LinkedHashMap<>();
    private final Map<String, String> kv = new LinkedHashMap<>();
    private long nextTxnId = 1;
    private int segIndex = 0;
    private long snapshotId = 0;
    private Journal.Segment segment;
    private final SimulationResult result = new SimulationResult();

    public Simulator(Path dir, CrashPlan plan) {
        this.dir = dir;
        this.plan = plan;
        this.result.activeSegment = 0;
    }

    public SimulationResult run(List<ScriptOp> script) throws IOException {
        wipe();
        Files.createDirectories(dir);
        segment = Journal.open(dir, segIndex);
        result.trace.add("创建日志段 " + Journal.segmentName(segIndex)
                + "（段头 " + SegmentHeader.SIZE + " 字节，含代次 " + segIndex + "）");

        for (int i = 0; i < script.size(); i++) {
            ScriptOp op = script.get(i);
            boolean crashed;
            switch (op.kind) {
                case BEGIN: crashed = doBegin(i, op); break;
                case PUT: crashed = doPut(i, op); break;
                case DELETE: crashed = doDelete(i, op); break;
                case COMMIT: crashed = doCommit(i, op); break;
                case CHECKPOINT: crashed = doCheckpoint(i); break;
                default: throw new IllegalStateException("unknown op " + op.kind);
            }
            if (crashed) return result;
            if (plan.crash && plan.anchor == CrashAnchor.AFTER_EVENT
                    && plan.eventIndex == i) {
                crash("事件 " + i + "（" + op + "）已完整落盘，随即掉电");
                return result;
            }
        }
        if (plan.crash && plan.anchor == CrashAnchor.AFTER_EVENT
                && plan.eventIndex == script.size() - 1) {
            crash("在最后一个事件之后掉电");
        } else if (plan.crash && plan.anchor == CrashAnchor.AFTER_RECORDS) {
            applyRecordsCrash();
        } else if (plan.crash && plan.anchor == CrashAnchor.AFTER_BYTES) {
            applyBytesCrash();
        }
        segment.close();
        result.activeSegment = segIndex;
        result.kvAtCrash.putAll(kv);
        return result;
    }

    private boolean doBegin(int eventIndex, ScriptOp op) throws IOException {
        long id = nextTxnId++;
        txnIds.put(op.txn, id);
        seq.put(id, 1);
        Frame frame = Frame.begin(id, 1);
        result.trace.add("BEGIN txn=" + op.txn + " -> 事务号 " + id + "，序号 1");
        return writeFrame(eventIndex, frame, "BEGIN txn=" + id);
    }

    private boolean doPut(int eventIndex, ScriptOp op) throws IOException {
        long id = requireTxn(op.txn);
        int s = seq.merge(id, 1, Integer::sum);
        Frame frame = Frame.put(id, s, op.key, op.value);
        result.trace.add("PUT txn=" + op.txn + "(id=" + id + ") key=" + op.key
                + " 序号 " + s + " 共 " + frame.totalSize() + " 字节");
        return writeFrame(eventIndex, frame, "PUT " + op.key + "=" + op.value);
    }

    private boolean doDelete(int eventIndex, ScriptOp op) throws IOException {
        long id = requireTxn(op.txn);
        int s = seq.merge(id, 1, Integer::sum);
        Frame frame = Frame.delete(id, s, op.key);
        result.trace.add("DELETE txn=" + op.txn + "(id=" + id + ") key=" + op.key
                + " 序号 " + s + " 共 " + frame.totalSize() + " 字节");
        return writeFrame(eventIndex, frame, "DELETE " + op.key);
    }

    private boolean doCommit(int eventIndex, ScriptOp op) throws IOException {
        long id = requireTxn(op.txn);
        int s = seq.merge(id, 1, Integer::sum);
        Frame frame = Frame.commit(id, s);
        result.trace.add("COMMIT txn=" + op.txn + "(id=" + id + ") 序号 " + s);
        boolean crashed = writeFrame(eventIndex, frame, "COMMIT txn=" + id);
        if (!crashed) {
            applyCommitted(id);
        }
        return crashed;
    }

    /** Applies a committed transaction's puts/deletes to the committed view. */
    private void applyCommitted(long id) {
        // Effects were captured when PUT/DELETE records were generated; for the
        // materialised reference state we rebuild by scanning own frames is complex,
        // so track pending ops instead (kept in pending map via recordPending).
        var pending = pendingOps.remove(id);
        if (pending == null) return;
        for (var change : pending) {
            if (change.value == null) {
                kv.remove(change.key);
            } else {
                kv.put(change.key, change.value);
            }
        }
    }

    private static final class Change {
        final String key;
        final String value;
        Change(String key, String value) {
            this.key = key;
            this.value = value;
        }
    }

    private final Map<Long, java.util.List<Change>> pendingOps = new LinkedHashMap<>();

    private long requireTxn(String label) {
        Long id = txnIds.get(label);
        if (id == null) {
            throw new IllegalArgumentException("事务 " + label + " 尚未 BEGIN");
        }
        return id;
    }

    /** Writes one frame, applying the fault configured for this event. Returns true if crashed. */
    private boolean writeFrame(int eventIndex, Frame frame, String description)
            throws IOException {
        // Track the pending effect for later commit application.
        if (frame.type() == FrameType.PUT || frame.type() == FrameType.DELETE) {
            String[] kvp = frame.type() == FrameType.PUT ? frame.decodeKv() : null;
            String key = frame.type() == FrameType.PUT ? kvp[0] : frame.decodeKey();
            String val = frame.type() == FrameType.PUT ? kvp[1] : null;
            pendingOps.computeIfAbsent(frame.txnId(), k -> new java.util.ArrayList<>())
                    .add(new Change(key, val));
        }

        if (plan.crash && plan.faultTarget == eventIndex) {
            return injectFault(frame, description);
        }
        segment.appendDurable(frame.encode());
        result.trace.add("  追加并 fsync：" + description + "（"
                + frame.totalSize() + " 字节，校验通过）");
        return false;
    }

    private boolean injectFault(Frame frame, String description) throws IOException {
        switch (plan.fault) {
            case SHORT_WRITE: {
                int written = segment.appendDurable(frame.encode(), plan.faultParam);
                result.trace.add("  [短写] " + description + " 仅 " + written
                        + "/" + frame.totalSize() + " 字节落盘后掉电");
                crash("短写故障：帧被截断在第 " + written + " 字节");
                return true;
            }
            case TRAIL_GARBAGE: {
                byte[] garbage = new byte[Math.max(1, plan.faultParam)];
                java.util.Arrays.fill(garbage, (byte) 0xFF);
                segment.appendDurable(garbage);
                result.trace.add("  [尾部垃圾] 写入 " + garbage.length
                        + " 字节 0xFF 垃圾后掉电");
                crash("尾部垃圾故障");
                return true;
            }
            case BIT_FLIP: {
                segment.appendDurable(frame.encodeWithBadCrc());
                result.trace.add("  [位翻转] " + description
                        + " 完整落盘但校验位被翻转，随后掉电");
                crash("位翻转故障：帧完整但校验失败");
                return true;
            }
            case UNKNOWN_TYPE: {
                byte[] data = frame.encode();
                data[4] = (byte) 0x77;
                int crcOffset = 4 + 1 + 8 + 4;
                int fixed = Crc32c.crc32(data, 0, crcOffset,
                        data, Frame.HEADER_SIZE, data.length - Frame.HEADER_SIZE);
                ByteBuffer.wrap(data, crcOffset, 4).putInt(fixed);
                segment.appendDurable(data);
                result.trace.add("  [未知类型] " + description
                        + " 完整落盘但类型字节=0x77（校验仍正确），随后掉电");
                crash("未知类型故障");
                return true;
            }
            default:
                segment.appendDurable(frame.encode());
                return false;
        }
    }

    private boolean doCheckpoint(int eventIndex) throws IOException {
        if (plan.crash && plan.faultTarget == eventIndex
                && plan.fault == FaultKind.CKPT_INTERRUPT) {
            return interruptedCheckpoint();
        }
        if (plan.crash && plan.anchor == CrashAnchor.CKPT_PHASE
                && plan.eventIndex == eventIndex) {
            return interruptedCheckpoint();
        }
        return fullCheckpoint();
    }

    private boolean fullCheckpoint() throws IOException {
        long id = ++snapshotId;
        int nextSeg = segIndex + 1;
        String digest = Digest.md5Hex(kv);
        result.trace.add("CHECKPOINT 代次 " + id + "：快照包含 " + kv.size()
                + " 个键，恢复后应从段 " + nextSeg + " 续读");

        Path temp = SnapshotStore.writeTemp(dir, id, nextTxnId, nextSeg, kv, -1, true);
        result.trace.add("  阶段1/5：写临时文件 " + temp.getFileName());
        result.trace.add("  阶段2/5：临时文件 fsync 完成");
        Path target = SnapshotStore.replaceTemp(dir, id);
        result.trace.add("  阶段3/5：原子替换为 " + target.getFileName() + "，目录 fsync");

        Frame marker = Frame.checkpoint(0, 0, nextTxnId, nextSeg, id, digest);
        segment.appendDurable(marker.encode());
        result.trace.add("  阶段4/5：旧段尾部写入 checkpoint 标记帧并 fsync");

        segment.close();
        segIndex = nextSeg;
        segment = Journal.open(dir, segIndex);
        result.trace.add("  阶段5/5：轮转创建新段 " + Journal.segmentName(segIndex)
                + "（段头代次 " + segIndex + "）");
        result.installedCheckpoints.add(id);
        result.activeSegment = segIndex;
        return false;
    }

    private boolean interruptedCheckpoint() throws IOException {
        long id = ++snapshotId;
        int nextSeg = segIndex + 1;
        String digest = Digest.md5Hex(kv);
        CheckpointStage stage = plan.stage;
        result.trace.add("CHECKPOINT 代次 " + id + " 被选为崩溃点（阶段：" + stage.label + "）");

        switch (stage) {
            case TEMP_WRITTEN: {
                SnapshotStore.writeTemp(dir, id, nextTxnId, nextSeg, kv,
                        Math.max(1, plan.faultParam), false);
                result.trace.add("  临时文件仅部分写入，未完成同步即掉电");
                crash("checkpoint 中断于阶段1（临时文件部分写入）");
                return true;
            }
            case TEMP_SYNCED: {
                SnapshotStore.writeTemp(dir, id, nextTxnId, nextSeg, kv, -1, true);
                result.trace.add("  完整临时文件已 fsync，但尚未替换，随即掉电");
                crash("checkpoint 中断于阶段2（临时文件已同步、未替换）");
                return true;
            }
            case REPLACED: {
                SnapshotStore.writeTemp(dir, id, nextTxnId, nextSeg, kv, -1, true);
                SnapshotStore.replaceTemp(dir, id);
                result.trace.add("  新快照已原子替换并同步，掉电发生在写标记帧之前");
                crash("checkpoint 中断于阶段3（已替换、未写日志标记）");
                return true;
            }
            case CKPT_LOGGED: {
                SnapshotStore.writeTemp(dir, id, nextTxnId, nextSeg, kv, -1, true);
                SnapshotStore.replaceTemp(dir, id);
                segment.appendDurable(
                        Frame.checkpoint(0, 0, nextTxnId, nextSeg, id, digest).encode());
                result.trace.add("  新快照与 checkpoint 标记帧均已落盘，掉电在轮转前");
                crash("checkpoint 中断于阶段4（标记帧已写、未轮转）");
                return true;
            }
            case ROTATED:
            default: {
                fullCheckpoint();
                crash("checkpoint 完成全部阶段后掉电");
                return true;
            }
        }
    }

    private void applyRecordsCrash() throws IOException {
        segment.close();
        Path file = segment.path();
        byte[] data = Files.readAllBytes(file);
        int keep = SegmentHeader.SIZE;
        int complete = 0;
        int cursor = SegmentHeader.SIZE;
        while (cursor < data.length && complete < plan.value) {
            ScanRecord rec = LogScanner.nextRecord(data, cursor);
            if (rec.outcome != ScanOutcome.VALID) break;
            keep = rec.offset + rec.consumed;
            cursor = keep;
            complete++;
        }
        truncate(file, keep);
        result.trace.add("掉电：活动段仅保留前 " + plan.value
                + " 条完整帧，截断到第 " + keep + " 字节");
        crash("保留 " + plan.value + " 条完整帧后掉电");
    }

    private void applyBytesCrash() throws IOException {
        segment.close();
        int target = Math.max(SegmentHeader.SIZE, plan.value);
        truncate(segment.path(), target);
        result.trace.add("掉电：活动段长度被截断为 " + target + " 字节");
        crash("在偏移 " + target + " 处掉电（按字节）");
    }

    private void truncate(Path file, int length) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.WRITE)) {
            long size = ch.size();
            if (length < size) {
                ch.truncate(length);
                ch.force(true);
            }
        }
    }

    private void crash(String reason) {
        result.crashed = true;
        result.crashReason = reason;
        result.activeSegment = segIndex;
        result.kvAtCrash.putAll(kv);
        try {
            if (segment != null && segment.position() >= 0) {
                segment.close();
            }
        } catch (IOException e) {
            // closing after simulated crash; ignore
        }
    }

    private void wipe() throws IOException {
        if (!Files.exists(dir)) return;
        try (var stream = Files.list(dir)) {
            try (var files = stream) {
                files.forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        }
    }
}
