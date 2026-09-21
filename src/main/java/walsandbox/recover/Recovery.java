package walsandbox.recover;

import walsandbox.frame.Frame;
import walsandbox.frame.FrameType;
import walsandbox.frame.FrameScanner;
import walsandbox.frame.ScanOutcome;
import walsandbox.frame.ScanStop;
import walsandbox.frame.ScannedFrame;
import walsandbox.log.SegmentInfo;
import walsandbox.log.SegmentLog;
import walsandbox.log.Snapshot;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Single recovery pass.
 *
 *  1. Read the complete snapshot (identity = generation, startSegmentId); a partial
 *     snapshot temp file is never considered.
 *  2. Walk the segment chain starting at the snapshot's start segment id (or segment 1).
 *     Links are proven by embedded SEGHDR frames, not by filenames; a missing segment or
 *     mismatched predecessor stops recovery.
 *  3. Within each segment, apply frames of committed transactions only, requiring
 *     consecutive per-transaction sequence numbers starting at BEGIN seq=1.
 *  4. A truncated frame, bad CRC, or unknown type ends scanning; bytes after it are
 *     never interpreted.
 *
 * The pass is read-only, so re-running recovery applies nothing twice.
 */
public final class Recovery {

    private static final class TxnState {
        boolean begun;
        boolean committed;
        long expectedSeq;
        boolean aborted;
        final Map<String, String> puts = new LinkedHashMap<>();
        final List<String> deletes = new ArrayList<>();
        long beginSegment;
        long commitSegment = -1;
        String abortReason;

        TreeMap<String, String> apply(TreeMap<String, String> kv) {
            for (Map.Entry<String, String> e : puts.entrySet()) {
                kv.put(e.getKey(), e.getValue());
            }
            for (String key : deletes) {
                kv.remove(key);
            }
            return kv;
        }
    }

    private final Path dir;
    private final List<EvidenceStep> steps = new ArrayList<>();
    private final List<SegmentScanView> scans = new ArrayList<>();
    private final List<Long> committedTxns = new ArrayList<>();
    private final List<Long> discardedTxns = new ArrayList<>();
    private final Map<Long, TxnState> txns = new LinkedHashMap<>();
    private final TreeMap<String, String> kv = new TreeMap<>();
    private int stepIndex;
    private int accepted;
    private int rejected;
    private TerminalStatus status = TerminalStatus.CLEAN;
    private String terminalReason = "";

    private Recovery(Path dir) {
        this.dir = dir;
    }

    public static RecoveryResult run(Path dir) throws IOException {
        return new Recovery(dir).execute();
    }

    private RecoveryResult execute() throws IOException {
        boolean snapshotUsed = false;
        long snapshotGeneration = 0;
        long startId = SegmentLog.FIRST_SEGMENT_ID;

        Snapshot.Contents snapshot = Snapshot.read(dir).orElse(null);
        if (snapshot == null) {
            addStep("snapshot", null, null, "未发现完整快照",
                    "snapshot.dat 不存在或不完整（临时文件不算快照），从最早段开始扫描，K/V 为空。",
                    kv);
        } else {
            snapshotUsed = true;
            snapshotGeneration = snapshot.generation();
            startId = snapshot.startSegmentId();
            kv.clear();
            kv.putAll(snapshot.kv());
            addStep("snapshot", null, null,
                    "加载完整快照 gen=" + snapshotGeneration,
                    "快照声明后续第一个连续段是 segment " + startId + "，直接继承 "
                            + kv.size() + " 个键；快照覆盖的操作不会被重做。",
                    kv);
        }

        Map<Long, SegmentInfo> all = SegmentLog.discover(dir);
        if (!all.containsKey(startId)) {
            status = TerminalStatus.START_SEGMENT_MISSING;
            terminalReason = "链首段 segment " + startId + " 不存在：存在的嵌入式段 id 为 "
                    + all.keySet();
            addStep("error", null, null, "链首段缺失", terminalReason, kv);
            finishDiscarded("链首段缺失，无法继续扫描");
            return result(snapshotUsed, snapshotGeneration, startId);
        }

        long segmentId = startId;
        long expectedPrev = startId == SegmentLog.FIRST_SEGMENT_ID
                ? SegmentLog.FIRST_SEGMENT_ID - 1 : startId - 1;
        SegmentInfo info = all.get(segmentId);
        boolean stop = false;

        while (!stop) {
            info = all.get(segmentId);
            if (info == null) {
                status = TerminalStatus.CHAIN_BROKEN;
                terminalReason = "段链在 segment " + segmentId + " 处出现缺口（发现的段 id："
                        + all.keySet() + "）";
                addStep("chain", segmentId, null, "日志段缺口", terminalReason, kv);
                break;
            }
            ScanOutcome scan = FrameScanner.scan(info.path(), 0);
            if (scan.frames().isEmpty() || scan.frames().get(0).frame().type()
                    != FrameType.SEGHDR) {
                status = TerminalStatus.CHAIN_BROKEN;
                terminalReason = "segment " + segmentId + " 缺少有效的 SEGHDR 段头";
                addStep("chain", segmentId, null, "段头无效", terminalReason, kv);
                break;
            }
            long[] hdr = scan.frames().get(0).frame().segHdrPayload();
            if (hdr[0] != segmentId || hdr[1] != expectedPrev) {
                status = TerminalStatus.CHAIN_BROKEN;
                terminalReason = "segment " + segmentId + " 段头声明 (id=" + hdr[0]
                        + ", prev=" + hdr[1] + ")，与期望链路不符";
                addStep("chain", segmentId, null, "链路校验失败", terminalReason, kv);
                break;
            }
            long bodyStart = scan.frames().get(0).endOffset();
            ScanOutcome body = FrameScanner.scan(info.path(), bodyStart);
            scans.add(new SegmentScanView(segmentId, expectedPrev, info.fileName(),
                    bodyStart, body));
            addStep("segment", segmentId, null, "扫描 " + info.fileName(),
                    "段头完整：segment " + segmentId + " 的前驱是 " + expectedPrev
                            + "，从字节 " + bodyStart + " 开始解释业务帧。", kv);

            for (ScannedFrame sf : body.frames()) {
                handleFrame(sf, segmentId);
            }
            accepted += body.frames().size();

            ScanStop s = body.stop();
            if (s != ScanStop.CLEAN_EOF) {
                recordTerminal(s, segmentId, body);
                rejected++;
                stop = true;
                break;
            }
            long next = segmentId + 1;
            if (all.containsKey(next)) {
                expectedPrev = segmentId;
                segmentId = next;
            } else {
                status = TerminalStatus.CLEAN;
                terminalReason = "扫描完 " + scans.size() + " 个连续段后到达干净 EOF";
                stop = true;
            }
        }

        finishDiscarded("扫描结束时事务没有完整 COMMIT（或 COMMIT 未通过校验），整笔丢弃");
        return result(snapshotUsed, snapshotGeneration, startId);
    }

    private void recordTerminal(ScanStop s, long segmentId, ScanOutcome body) {
        status = switch (s) {
            case TRUNCATED_HEADER -> TerminalStatus.TRUNCATED_HEADER;
            case TRUNCATED_PAYLOAD -> TerminalStatus.TRUNCATED_PAYLOAD;
            case BAD_CRC -> TerminalStatus.BAD_CRC;
            case BAD_LENGTH -> TerminalStatus.BAD_LENGTH;
            case UNKNOWN_TYPE -> TerminalStatus.UNKNOWN_TYPE;
            case CLEAN_EOF -> TerminalStatus.CLEAN;
        };
        String label = switch (s) {
            case TRUNCATED_HEADER -> "截断帧：头部不完整";
            case TRUNCATED_PAYLOAD -> "截断帧：载荷不完整";
            case BAD_CRC -> "校验失败：丢弃该帧并停止";
            case BAD_LENGTH -> "非法长度：丢弃该帧并停止";
            case UNKNOWN_TYPE -> "未知类型：丢弃该帧并停止";
            case CLEAN_EOF -> "干净 EOF";
        };
        terminalReason = "segment " + segmentId + " " + body.stopExplanation();
        addStep("terminal", segmentId, body.stopOffset(), label,
                body.stop().description() + "。" + body.stopExplanation()
                        + "；其后所有字节都不再解释。", kv);
    }

    private void handleFrame(ScannedFrame sf, long segmentId) {
        Frame f = sf.frame();
        long txn = f.txnId();
        switch (f.type()) {
            case BEGIN -> {
                if (f.seq() != 1) {
                    addStep("frame", segmentId, sf.offset(),
                            "丢弃 BEGIN txn=" + txn,
                            "BEGIN 的序号必须是 1，实际是 " + f.seq() + "。", kv);
                    return;
                }
                TxnState st = new TxnState();
                st.begun = true;
                st.expectedSeq = 2;
                st.beginSegment = segmentId;
                txns.put(txn, st);
                addStep("frame", segmentId, sf.offset(),
                        "BEGIN txn=" + txn,
                        "事务开始，期待下一条记录 seq=2；暂不改 K/V。", kv);
            }
            case PUT -> {
                TxnState st = txns.get(txn);
                Frame.KeyValue kvp = f.putPayload();
                if (!live(st, txn, f, segmentId, sf.offset())) {
                    return;
                }
                st.puts.put(kvp.key(), kvp.value());
                addStep("frame", segmentId, sf.offset(),
                        "PUT txn=" + txn + " seq=" + f.seq() + " " + kvp.key() + "="
                                + kvp.value(),
                        "记录进事务暂存区（seq=" + f.seq() + " 连续），提交前 K/V 不变。",
                        kv);
                st.expectedSeq++;
            }
            case DELETE -> {
                TxnState st = txns.get(txn);
                String key = f.deletePayload();
                if (!live(st, txn, f, segmentId, sf.offset())) {
                    return;
                }
                st.puts.remove(key);
                st.deletes.add(key);
                addStep("frame", segmentId, sf.offset(),
                        "DELETE txn=" + txn + " seq=" + f.seq() + " " + key,
                        "记录进事务暂存区，提交前 K/V 不变。", kv);
                st.expectedSeq++;
            }
            case COMMIT -> {
                TxnState st = txns.get(txn);
                if (!live(st, txn, f, segmentId, sf.offset())) {
                    return;
                }
                if (f.seq() != st.expectedSeq) {
                    st.aborted = true;
                    st.abortReason = "COMMIT 序号 " + f.seq() + " 与期待 " + st.expectedSeq
                            + " 不连续";
                    addStep("abort", segmentId, sf.offset(),
                            "丢弃 txn=" + txn,
                            st.abortReason + "，整笔事务作废。", kv);
                    return;
                }
                st.committed = true;
                st.commitSegment = segmentId;
                st.apply(kv);
                committedTxns.add(txn);
                addStep("commit", segmentId, sf.offset(),
                        "COMMIT txn=" + txn + " 生效",
                        "COMMIT 帧完整且 CRC 通过，按序施加 " + st.puts.size()
                                + " 个写入和 " + st.deletes.size()
                                + " 个删除；事务此刻才生效。", kv);
            }
            case CHECKPOINT -> {
                long[] cp = f.checkpointPayload();
                addStep("checkpoint-frame", segmentId, sf.offset(),
                        "CHECKPOINT 帧 gen=" + cp[0],
                        "日志中的检查点标记仅与 snapshot.dat 配对；K/V 以快照为准，不重复施加。",
                        kv);
            }
            case SEGHDR -> addStep("frame", segmentId, sf.offset(), "SEGHDR",
                    "段头已在链校验阶段消费。", kv);
        }
    }

    private boolean live(TxnState st, long txn, Frame f, long segmentId, long offset) {
        if (st == null || !st.begun) {
            addStep("frame", segmentId, offset,
                    "忽略 txn=" + txn + " 的 " + f.type(),
                    "没有有效 BEGIN，孤立记录被忽略。", kv);
            return false;
        }
        if (st.aborted) {
            return false;
        }
        if (st.committed) {
            st.aborted = true;
            st.abortReason = "COMMIT 之后又出现同事务记录";
            addStep("abort", segmentId, offset,
                    "丢弃 txn=" + txn,
                    "事务已经 COMMIT，后续记录违反协议，整笔作废。", kv);
            return false;
        }
        if (f.seq() != st.expectedSeq) {
            st.aborted = true;
            st.abortReason = "记录序号 " + f.seq() + "，期待 " + st.expectedSeq;
            addStep("abort", segmentId, offset,
                    "丢弃 txn=" + txn,
                    "序号不连续（实际 " + f.seq() + "，期待 " + st.expectedSeq
                            + "），整笔事务作废。", kv);
            return false;
        }
        return true;
    }

    private void finishDiscarded(String reason) {
        for (Map.Entry<Long, TxnState> e : txns.entrySet()) {
            TxnState st = e.getValue();
            if (!st.committed && !discardedTxns.contains(e.getKey())) {
                discardedTxns.add(e.getKey());
                addStep("discard", st.beginSegment, null,
                        "丢弃未提交事务 txn=" + e.getKey(), reason, kv);
            }
        }
    }

    private RecoveryResult result(boolean snapshotUsed, long snapshotGeneration,
                                  long startId) {
        return new RecoveryResult(snapshotUsed, snapshotGeneration, startId,
                new TreeMap<>(kv), List.copyOf(steps), List.copyOf(scans),
                List.copyOf(committedTxns), List.copyOf(discardedTxns), status,
                terminalReason, accepted, rejected);
    }

    private void addStep(String kind, Long segmentId, Long offset, String title,
                         String detail, TreeMap<String, String> kvState) {
        steps.add(new EvidenceStep(++stepIndex, kind, segmentId, offset, title, detail,
                Map.copyOf(kvState)));
    }
}
