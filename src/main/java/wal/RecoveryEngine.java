package wal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Read-only crash recovery.
 *
 * <ol>
 *   <li>Inspect every snapshot candidate and pick the highest generation that
 *       verifies as a complete snapshot. Anything torn is named but never used,
 *       so the choice is always whole-old or whole-new.</li>
 *   <li>Read the embedded generation from every segment header (never the
 *       filename) and take the contiguous run starting at the snapshot's
 *       {@code nextSegIndex}; a missing generation with later generations on
 *       disk is a gap and stops replay before any unbacked segment.</li>
 *   <li>Scan each chosen segment frame by frame. Only complete, checksum-valid
 *       frames participate. A truncated/garbage/corrupt/unknown frame ends
 *       replay at that point.</li>
 *   <li>A transaction is redone only when it has BEGIN..COMMIT with continuous
 *       per-transaction sequence numbers and no structural violations.</li>
 * </ol>
 *
 * <p>Recovery writes nothing: running it twice over the same files yields the
 * same state, and operations already covered by the selected snapshot are
 * physically never re-read.
 */
public final class RecoveryEngine {

    private final Path dir;
    private final RecoveryReport report = new RecoveryReport();
    private final Map<Long, RecoveredTxn> txns = new LinkedHashMap<>();

    public RecoveryEngine(Path dir) {
        this.dir = dir;
    }

    public RecoveryReport recover() throws IOException {
        Files.createDirectories(dir);

        inspectSnapshots();

        // Identify segments by embedded header, not by filename.
        TreeMap<Integer, Path> segmentsByIndex = new TreeMap<>();
        for (Journal.SegmentFile seg : Journal.listSegments(dir)) {
            segmentsByIndex.put(seg.index, seg.path);
            report.segmentDecisions.add("识别段文件 " + seg.path.getFileName()
                    + "：段头代次 = " + seg.index);
        }
        inspectUnrecognizedFiles(segmentsByIndex);

        int start = report.baseSegment < 0 ? 0 : report.baseSegment;
        if (report.baseSegment > 0) {
            report.diagnostics.add("快照要求从代次 " + report.baseSegment
                    + " 的连续段续读；更早的段包含已 checkpoint 的操作，将被跳过。");
        }

        int expected = start;
        Integer highest = segmentsByIndex.isEmpty() ? null : segmentsByIndex.lastKey();
        while (true) {
            Path path = segmentsByIndex.get(expected);
            if (path == null) {
                if (highest != null && highest > expected) {
                    report.gap = true;
                    report.segmentDecisions.add("段代次缺口：需要 " + expected
                            + " 但文件缺失，磁盘上却存在代次 " + highest
                            + "。拒绝按文件名猜测，恢复在此停止。");
                    report.replayStopSegment = expected;
                } else {
                    report.segmentDecisions.add("代次 " + expected
                            + " 是干净结尾：之后没有更多日志段。");
                }
                break;
            }
            ScanOutcome outcome = replaySegment(path, expected);
            report.replayStopSegment = expected;
            report.replayStopReason = outcome;
            if (outcome != ScanOutcome.VALID && outcome != ScanOutcome.CLEAN_EOF) {
                report.segmentDecisions.add("段 " + path.getFileName()
                        + " 在扫描到 " + outcome + " 时停止，后续段不再读取。");
                break;
            }
            expected++;
            report.replayStopReason = ScanOutcome.CLEAN_EOF;
        }

        finalizeTransactions();
        return report;
    }

    private void inspectSnapshots() throws IOException {
        var candidates = SnapshotStore.inspect(dir);
        candidates.sort(Comparator.comparingLong(c -> c.id));
        long bestId = Long.MIN_VALUE;
        SnapshotFormat.Snapshot best = null;
        for (SnapshotStore.Candidate c : candidates) {
            String name = c.path.getFileName().toString();
            boolean temp = name.endsWith(SnapshotStore.TEMP_SUFFIX);
            if (c.complete && !temp && c.snapshot.snapshotId > bestId) {
                bestId = c.snapshot.snapshotId;
                best = c.snapshot;
            }
            String verdict = c.complete
                    ? (temp ? "完整但仍是临时文件（不采用）" : "完整快照，候选")
                    : "不完整/校验失败，绝不采用";
            report.diagnostics.add("快照候选 " + name + "（代次 " + c.id + "）：" + verdict);
        }
        if (best != null) {
            report.snapshotId = best.snapshotId;
            report.baseSegment = best.nextSegIndex;
            report.kv.putAll(best.kv);
            report.snapshotDigest = Digest.md5Hex(best.kv);
            report.selectedSnapshot = SnapshotStore.snapshotName(best.snapshotId);
            report.diagnostics.add("采用快照 " + report.selectedSnapshot
                    + "：" + best.kv.size() + " 个键，nextTxnId=" + best.nextTxnId
                    + "，nextSegIndex=" + best.nextSegIndex + "。");
        } else {
            report.diagnostics.add("没有可用快照，从空键值状态与段代次 0 开始。");
        }
    }

    private void inspectUnrecognizedFiles(Map<Integer, Path> known) throws IOException {
        for (Path file : Journal.listAllFiles(dir)) {
            String name = file.getFileName().toString();
            if (known.containsValue(file)) continue;
            if (name.startsWith(SnapshotStore.SNAPSHOT_PREFIX)) continue;
            long size = Files.size(file);
            if (size < SegmentHeader.SIZE) {
                report.segmentDecisions.add("文件 " + name + " 只有 " + size
                        + " 字节，段头被截断：忽略该文件。");
            } else {
                report.segmentDecisions.add("文件 " + name
                        + " 的段头魔数不匹配：忽略，绝不按文件名推断代次。");
            }
        }
    }

    private ScanOutcome replaySegment(Path path, int index) throws IOException {
        byte[] data = Files.readAllBytes(path);
        if (data.length < SegmentHeader.SIZE) {
            report.segmentDecisions.add("段 " + path.getFileName() + " 段头截断，跳过。");
            return ScanOutcome.TRUNCATED_HEADER;
        }
        SegmentHeader header = SegmentHeader.decode(data);
        if (header == null) {
            report.segmentDecisions.add("段 " + path.getFileName()
                    + " 段头魔数错误，跳过。");
            return ScanOutcome.IMPLAUSIBLE_LENGTH;
        }
        int cursor = SegmentHeader.SIZE;
        while (cursor < data.length) {
            ScanRecord rec = LogScanner.nextRecord(data, cursor);
            switch (rec.outcome) {
                case VALID:
                    handleFrame(rec.frame, index);
                    cursor = rec.offset + rec.consumed;
                    break;
                case CLEAN_EOF:
                    return ScanOutcome.CLEAN_EOF;
                default:
                    report.diagnostics.add("段 " + path.getFileName()
                            + " 偏移 " + rec.offset + " 扫描结果：" + rec.outcome
                            + describeLength(rec) + " —— 重放在此帧之前停止。");
                    return rec.outcome;
            }
        }
        return ScanOutcome.CLEAN_EOF;
    }

    private String describeLength(ScanRecord rec) {
        if (rec.declaredPayloadLength >= 0) {
            return "（载荷长度字段=" + rec.declaredPayloadLength + "）";
        }
        return "";
    }

    private void handleFrame(Frame frame, int segmentIndex) {
        if (frame.type() == FrameType.CHECKPOINT) {
            report.diagnostics.add("段代次 " + segmentIndex + " 中的 checkpoint 标记帧："
                    + "仅作为轮转标记（键值已在快照中），不重复施加。");
            return;
        }
        RecoveredTxn txn = txns.computeIfAbsent(frame.txnId(), RecoveredTxn::new);
        switch (frame.type()) {
            case BEGIN:
                if (txn.begun) {
                    reject(txn, "重复 BEGIN");
                }
                if (frame.seqNo() != 1) {
                    reject(txn, "BEGIN 序号不是 1（实际 " + frame.seqNo() + "）");
                }
                txn.begun = true;
                txn.expectedSeq = 2;
                break;
            case PUT:
            case DELETE:
                if (!txn.begun) {
                    reject(txn, frame.type() + " 出现在 BEGIN 之前");
                }
                if (txn.expectedSeq >= 0 && frame.seqNo() != txn.expectedSeq) {
                    reject(txn, "序号不连续：期望 " + txn.expectedSeq
                            + "，实际 " + frame.seqNo());
                }
                txn.expectedSeq = frame.seqNo() + 1;
                if (frame.type() == FrameType.PUT && frame.decodeKv() == null) {
                    reject(txn, "PUT 载荷格式损坏");
                }
                if (!txn.rejected) {
                    txn.ops.add(frame);
                }
                break;
            case COMMIT:
                if (!txn.begun) {
                    reject(txn, "没有 BEGIN 的 COMMIT");
                }
                if (frame.seqNo() != txn.expectedSeq) {
                    reject(txn, "COMMIT 序号 " + frame.seqNo()
                            + " 与连续序号（期望 " + txn.expectedSeq + "）不符");
                }
                if (txn.rejected) {
                    report.txnDecisions.add("事务 " + txn.id
                            + " 的 COMMIT 完整，但此前已有结构问题（"
                            + txn.rejectReason + "）：丢弃。");
                } else {
                    txn.committed = true;
                }
                break;
            default:
                break;
        }
    }

    private void reject(RecoveredTxn txn, String reason) {
        if (!txn.rejected) {
            txn.rejected = true;
            txn.rejectReason = reason;
        }
    }

    private void finalizeTransactions() {
        for (RecoveredTxn txn : txns.values()) {
            if (txn.committed && !txn.rejected) {
                int puts = 0;
                int deletes = 0;
                for (Frame op : txn.ops) {
                    if (op.type() == FrameType.PUT) {
                        String[] kvp = op.decodeKv();
                        report.kv.put(kvp[0], kvp[1]);
                        puts++;
                    } else {
                        report.kv.remove(op.decodeKey());
                        deletes++;
                    }
                }
                report.redoneTransactions++;
                report.txnDecisions.add("事务 " + txn.id
                        + "：BEGIN..COMMIT 完整、序号连续 → 重做（"
                        + puts + " 次 put，" + deletes + " 次 delete）。");
            } else if (!txn.committed) {
                report.droppedTransactions++;
                String why = txn.rejected ? txn.rejectReason : "COMMIT 未完整落盘（未提交）";
                report.txnDecisions.add("事务 " + txn.id + "：丢弃（" + why + "）。");
            } else {
                report.droppedTransactions++;
                report.txnDecisions.add("事务 " + txn.id + "：丢弃（"
                        + txn.rejectReason + "）。");
            }
        }
    }
}
