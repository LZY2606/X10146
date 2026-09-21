package wal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CheckpointTest {

    @TempDir
    Path temp;

    private Path dir(String name) throws IOException {
        Path d = temp.resolve(name);
        Files.createDirectories(d);
        return d;
    }

    private List<ScriptOp> twoTxnsThenCheckpointThenMore() {
        List<ScriptOp> s = new ArrayList<>();
        s.add(ScriptOp.begin("t1"));
        s.add(ScriptOp.put("t1", "a", "1"));
        s.add(ScriptOp.commit("t1"));
        s.add(ScriptOp.checkpoint());
        s.add(ScriptOp.begin("t2"));
        s.add(ScriptOp.put("t2", "b", "2"));
        s.add(ScriptOp.delete("t2", "a"));
        s.add(ScriptOp.commit("t2"));
        return s;
    }

    @Test
    void cleanRunWithCheckpointRecoversBothPreAndPostState() throws IOException {
        Path d = dir("clean-ckpt");
        new Simulator(d, CrashPlan.none()).run(twoTxnsThenCheckpointThenMore());
        RecoveryReport report = new RecoveryEngine(d).recover();
        assertEquals("2", report.kv.get("b"));
        assertFalse(report.kv.containsKey("a"));
        assertEquals(1, report.snapshotId);
        assertEquals(1, report.baseSegment);
        assertEquals(1, report.redoneTransactions);
    }

    @Test
    void repeatedRecoveryIsStableAndDoesNotReapplyCheckpointedOps() throws IOException {
        Path d = dir("repeat");
        new Simulator(d, CrashPlan.none()).run(twoTxnsThenCheckpointThenMore());
        RecoveryReport first = new RecoveryEngine(d).recover();
        RecoveryReport second = new RecoveryEngine(d).recover();
        RecoveryReport third = new RecoveryEngine(d).recover();
        assertEquals(first.kv, second.kv);
        assertEquals(second.kv, third.kv);
        // Files on disk are untouched by recovery (read-only).
        long filesAfter1 = Files.list(d).count();
        new RecoveryEngine(d).recover();
        assertEquals(filesAfter1, Files.list(d).count());
        // t1's put lives only in the snapshot; it must never be counted as a redone txn.
        assertEquals(1, third.redoneTransactions,
                "checkpoint 前已提交的事务不应在日志重放中被重复施加");
    }

    @Test
    void checkpointCrashDuringTempWriteKeepsOldSnapshot() throws IOException {
        Path d = dir("ckpt-stage1");
        // checkpoint 1 installs cleanly; checkpoint 2 is interrupted at phase 1.
        List<ScriptOp> s = new ArrayList<>();
        s.add(ScriptOp.begin("t1"));
        s.add(ScriptOp.put("t1", "a", "1"));
        s.add(ScriptOp.commit("t1"));
        s.add(ScriptOp.checkpoint());
        s.add(ScriptOp.begin("t2"));
        s.add(ScriptOp.put("t2", "b", "2"));
        s.add(ScriptOp.commit("t2"));
        s.add(ScriptOp.checkpoint());
        CrashPlan plan = new CrashPlan();
        plan.crash = true;
        plan.anchor = CrashAnchor.CKPT_PHASE;
        plan.eventIndex = s.size() - 1;
        plan.stage = CheckpointStage.TEMP_WRITTEN;
        plan.faultParam = 17;
        new Simulator(d, plan).run(s);

        RecoveryReport report = new RecoveryEngine(d).recover();
        // old complete snapshot wins; torn temp is present but ignored
        assertEquals(1, report.snapshotId);
        assertEquals("1", report.kv.get("a"));
        // t2 was committed durably before checkpoint 2 began, so replay from
        // the old snapshot's nextSegIndex legitimately redoes it.
        assertEquals("2", report.kv.get("b"));
        // the torn temp for generation 2 is present but can never be selected
        assertTrue(Files.list(d).anyMatch(p -> p.getFileName().toString().endsWith(".tmp")));
        assertTrue(report.diagnostics.stream().anyMatch(x -> x.contains("不完整")));
    }

    @Test
    void checkpointCrashAfterTempSyncButBeforeReplaceKeepsOldSnapshot()
            throws IOException {
        Path d = dir("ckpt-stage2");
        List<ScriptOp> s = new ArrayList<>();
        s.add(ScriptOp.begin("t1"));
        s.add(ScriptOp.put("t1", "a", "1"));
        s.add(ScriptOp.commit("t1"));
        s.add(ScriptOp.checkpoint());
        s.add(ScriptOp.begin("t2"));
        s.add(ScriptOp.put("t2", "b", "2"));
        s.add(ScriptOp.commit("t2"));
        s.add(ScriptOp.checkpoint());
        CrashPlan plan = new CrashPlan();
        plan.crash = true;
        plan.anchor = CrashAnchor.CKPT_PHASE;
        plan.eventIndex = s.size() - 1;
        plan.stage = CheckpointStage.TEMP_SYNCED;
        new Simulator(d, plan).run(s);

        RecoveryReport report = new RecoveryEngine(d).recover();
        assertEquals(1, report.snapshotId);
        assertEquals("1", report.kv.get("a"));
        assertEquals("2", report.kv.get("b"));
        // complete but unreplaced temp file exists; old whole snapshot still chosen
        assertTrue(Files.list(d).anyMatch(p -> p.getFileName().toString().endsWith(".tmp")));
    }

    @Test
    void checkpointCrashAfterReplacePicksNewSnapshot() throws IOException {
        Path d = dir("ckpt-stage3");
        List<ScriptOp> s = new ArrayList<>();
        s.add(ScriptOp.begin("t1"));
        s.add(ScriptOp.put("t1", "a", "1"));
        s.add(ScriptOp.commit("t1"));
        s.add(ScriptOp.checkpoint());
        s.add(ScriptOp.begin("t2"));
        s.add(ScriptOp.put("t2", "b", "2"));
        s.add(ScriptOp.commit("t2"));
        s.add(ScriptOp.checkpoint());
        CrashPlan plan = new CrashPlan();
        plan.crash = true;
        plan.anchor = CrashAnchor.CKPT_PHASE;
        plan.eventIndex = s.size() - 1;
        plan.stage = CheckpointStage.REPLACED;
        new Simulator(d, plan).run(s);

        RecoveryReport report = new RecoveryEngine(d).recover();
        assertEquals(2, report.snapshotId);
        assertEquals("1", report.kv.get("a"));
        assertEquals("2", report.kv.get("b"));
        // rotation marker not written yet, but snapshot says continue from gen 2
        assertEquals(2, report.baseSegment);
    }

    @Test
    void logSegmentGapStopsReplay() throws IOException {
        Path d = dir("gap");
        new Simulator(d, CrashPlan.none()).run(twoTxnsThenCheckpointThenMore());
        // snapshot points at segment 2: simulate that the post-checkpoint segment
        // vanished but a later generation exists.
        Path seg1 = d.resolve(Journal.segmentName(1));
        Path seg2 = d.resolve(Journal.segmentName(2));
        Files.delete(seg1);
        try (Journal.Segment s = Journal.open(d, 2)) {
            // header only; its mere existence with gen 2 proves the gap at 1
        }
        RecoveryReport report = new RecoveryEngine(d).recover();
        assertTrue(report.gap);
        // snapshot state remains intact; nothing from the missing run is guessed
        assertEquals("1", report.kv.get("a"));
        assertNull(report.kv.get("b"));
        assertTrue(report.segmentDecisions.stream().anyMatch(s -> s.contains("缺口")));
    }

    @Test
    void renamedSegmentIsIdentifiedByEmbeddedHeader() throws IOException {
        Path d = dir("rename");
        new Simulator(d, CrashPlan.none()).run(twoTxnsThenCheckpointThenMore());
        Path seg1 = d.resolve(Journal.segmentName(1));
        Path renamed = d.resolve("i-am-not-a-segment.dat");
        Files.move(seg1, renamed);
        RecoveryReport report = new RecoveryEngine(d).recover();
        // the file is still recognised by its embedded generation index
        assertEquals("2", report.kv.get("b"));
        assertTrue(report.segmentDecisions.stream()
                .anyMatch(s -> s.contains("i-am-not-a-segment.dat") && s.contains("代次 = 1")));
    }

    @Test
    void nonContiguousTransactionSequenceRejectsTxn() throws IOException {
        Path d = dir("seq-gap");
        try (Journal.Segment s = Journal.open(d, 0)) {
            s.appendDurable(Frame.begin(7, 1).encode());
            s.appendDurable(Frame.put(7, 2, "a", "1").encode());
            // missing seq 3, commit claims seq 4
            s.appendDurable(Frame.commit(7, 4).encode());
        }
        RecoveryReport report = new RecoveryEngine(d).recover();
        assertNull(report.kv.get("a"));
        assertEquals(1, report.droppedTransactions);
        assertTrue(report.txnDecisions.get(0).contains("序号"));
    }
}
