package wal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RecoveryTest {

    @TempDir
    Path temp;

    private Path dir(String name) throws IOException {
        Path d = temp.resolve(name);
        Files.createDirectories(d);
        return d;
    }

    private List<ScriptOp> committed(String txn, String k, String v) {
        return List.of(
                ScriptOp.begin(txn), ScriptOp.put(txn, k, v), ScriptOp.commit(txn));
    }

    private SimulationResult simulate(Path d, List<ScriptOp> script, CrashPlan plan)
            throws IOException {
        return new Simulator(d, plan == null ? CrashPlan.none() : plan).run(script);
    }

    private RecoveryReport recover(Path d) throws IOException {
        return new RecoveryEngine(d).recover();
    }

    @Test
    void emptyLogRecoversEmptyKv() throws IOException {
        Path d = dir("empty");
        CrashPlan plan = CrashPlan.none();
        simulate(d, List.of(), plan);
        RecoveryReport report = recover(d);
        assertTrue(report.kv.isEmpty());
        assertEquals(0, report.redoneTransactions);
        assertEquals(ScanOutcome.CLEAN_EOF, report.replayStopReason);
        assertTrue(report.diagnostics.stream().anyMatch(s -> s.contains("没有可用快照")));
    }

    @Test
    void truncatedHeaderIsDetected() throws IOException {
        Path d = dir("trunc-head");
        // one committed txn plus a second BEGIN whose frame is torn to 8 bytes
        CrashPlan plan = new CrashPlan();
        plan.crash = true;
        plan.anchor = CrashAnchor.AFTER_RECORDS;
        plan.value = 3; // begin,put,commit of t1; next frame not started -> clean
        List<ScriptOp> script = new java.util.ArrayList<>();
        script.addAll(committed("t1", "a", "1"));
        script.add(ScriptOp.begin("t2"));
        SimulationResult sim = simulate(d, script, plan);
        assertTrue(sim.crashed);

        Path seg = d.resolve(Journal.segmentName(0));
        byte[] data = Files.readAllBytes(seg);
        // Manually append 8 durable bytes (a torn next frame header), then recover.
        try (FileChannel ch = FileChannel.open(seg, StandardOpenOption.WRITE,
                StandardOpenOption.APPEND)) {
            ch.write(java.nio.ByteBuffer.wrap(new byte[8]));
            ch.force(true);
        }
        RecoveryReport report = recover(d);
        assertEquals(ScanOutcome.TRUNCATED_HEADER, report.replayStopReason);
        assertEquals("1", report.kv.get("a"));
    }

    @Test
    void truncatedPayloadIsDetected() throws IOException {
        Path d = dir("trunc-body");
        Path seg0;
        {
            simulate(d, committed("t1", "a", "1"), CrashPlan.none());
        }
        // append a complete t2 begin then a torn PUT with only 10 payload bytes
        try (Journal.Segment s = Journal.open(d, 0)) {
            long id2 = 2L;
            s.appendDurable(Frame.begin(id2, 1).encode());
            byte[] put = Frame.put(id2, 2, "long-key", "long-value").encode();
            try (FileChannel ch = FileChannel.open(s.path(), StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND)) {
                ch.write(java.nio.ByteBuffer.wrap(put, 0, Frame.HEADER_SIZE + 3));
                ch.force(true);
            }
        }
        RecoveryReport report = recover(d);
        assertEquals(ScanOutcome.TRUNCATED_PAYLOAD, report.replayStopReason);
        assertEquals("1", report.kv.get("a"));
        assertNull(report.kv.get("long-key"));
    }

    @Test
    void badChecksumFrameIsNotApplied() throws IOException {
        Path d = dir("bad-crc");
        simulate(d, committed("t1", "a", "1"), CrashPlan.none());
        try (Journal.Segment s = Journal.open(d, 0)) {
            // complete t2 commit chain but flip crc on the commit frame
            s.appendDurable(Frame.begin(2, 1).encode());
            s.appendDurable(Frame.put(2, 2, "b", "2").encode());
            s.appendDurable(Frame.commit(2, 3).encodeWithBadCrc());
        }
        RecoveryReport report = recover(d);
        assertEquals(ScanOutcome.CHECKSUM_MISMATCH, report.replayStopReason);
        assertEquals(1, report.redoneTransactions);
        assertNull(report.kv.get("b"));
    }

    @Test
    void unknownTypeStopsReplay() throws IOException {
        Path d = dir("unknown");
        simulate(d, committed("t1", "a", "1"), CrashPlan.none());
        try (Journal.Segment s = Journal.open(d, 0)) {
            byte[] f = Frame.begin(2, 1).encode();
            f[4] = 0x77;
            int crcOffset = 4 + 1 + 8 + 4;
            int crc = Crc32c.crc32(f, 0, crcOffset, f, Frame.HEADER_SIZE,
                    f.length - Frame.HEADER_SIZE);
            java.nio.ByteBuffer.wrap(f, crcOffset, 4).putInt(crc);
            s.appendDurable(f);
        }
        RecoveryReport report = recover(d);
        assertEquals(ScanOutcome.UNKNOWN_TYPE, report.replayStopReason);
        assertEquals("1", report.kv.get("a"));
    }

    @Test
    void uncommittedTransactionIsDropped() throws IOException {
        Path d = dir("uncommitted");
        List<ScriptOp> script = new java.util.ArrayList<>();
        script.addAll(committed("t1", "a", "1"));
        script.add(ScriptOp.begin("t2"));
        script.add(ScriptOp.put("t2", "b", "2")); // no commit; crash after this
        CrashPlan plan = new CrashPlan();
        plan.crash = true;
        plan.anchor = CrashAnchor.AFTER_EVENT;
        plan.eventIndex = script.size() - 1;
        simulate(d, script, plan);
        RecoveryReport report = recover(d);
        assertEquals("1", report.kv.get("a"));
        assertNull(report.kv.get("b"));
        assertEquals(1, report.droppedTransactions);
    }

    @Test
    void shortWriteFaultLeavesTornFrame() throws IOException {
        Path d = dir("short");
        List<ScriptOp> script = committed("t1", "a", "1");
        CrashPlan plan = new CrashPlan();
        plan.crash = true;
        plan.fault = FaultKind.SHORT_WRITE;
        plan.faultTarget = 1; // PUT frame
        plan.faultParam = Frame.HEADER_SIZE + 2;
        SimulationResult sim = simulate(d, script, plan);
        assertTrue(sim.crashed);
        RecoveryReport report = recover(d);
        assertTrue(report.kv.isEmpty());
        assertEquals(ScanOutcome.TRUNCATED_PAYLOAD, report.replayStopReason);
    }

    @Test
    void tailGarbageLeadsToImplausibleLength() throws IOException {
        Path d = dir("garbage");
        List<ScriptOp> script = committed("t1", "a", "1");
        CrashPlan plan = new CrashPlan();
        plan.crash = true;
        plan.fault = FaultKind.TRAIL_GARBAGE;
        plan.faultTarget = 0;
        plan.faultParam = 24; // full header of 0xFF => huge length
        simulate(d, script, plan);
        RecoveryReport report = recover(d);
        assertEquals(ScanOutcome.IMPLAUSIBLE_LENGTH, report.replayStopReason);
    }
}
