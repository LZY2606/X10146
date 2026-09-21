package walsandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import walsandbox.frame.Frame;
import walsandbox.frame.FrameScanner;
import walsandbox.frame.ScanOutcome;
import walsandbox.frame.ScanStop;
import walsandbox.log.SegmentLog;
import walsandbox.log.Snapshot;
import walsandbox.recover.Recovery;
import walsandbox.recover.RecoveryResult;
import walsandbox.recover.TerminalStatus;
import walsandbox.sandbox.CrashSpec;
import walsandbox.sandbox.Event;
import walsandbox.sandbox.Fault;
import walsandbox.sandbox.Materializer;
import walsandbox.sandbox.SandboxStore;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WalSandboxTest {

    @TempDir
    Path tmp;

    private int id = 1;
    private long txn = 1;

    private Event ev(List<Event> prior, String type, String key, String value) {
        long txnId;
        long seq;
        if (type.equals("BEGIN")) {
            txnId = txn++;
            seq = 1;
        } else if (type.equals("CHECKPOINT")) {
            txnId = 0;
            seq = 0;
        } else {
            txnId = txn - 1;
            seq = prior.stream().filter(e -> e.txnId() == txnId).count() + 1;
        }
        return new Event(id++, type, txnId, seq, key, value, Fault.none());
    }

    private List<Event> oneCommitted() {
        List<Event> e = new ArrayList<>();
        e.add(ev(e, "BEGIN", null, null));
        e.add(ev(e, "PUT", "a", "1"));
        e.add(ev(e, "COMMIT", null, null));
        return e;
    }

    private List<Event> twoCommitted() {
        List<Event> e = new ArrayList<>();
        e.add(ev(e, "BEGIN", null, null));
        e.add(ev(e, "PUT", "a", "1"));
        e.add(ev(e, "PUT", "b", "2"));
        e.add(ev(e, "COMMIT", null, null));
        e.add(ev(e, "BEGIN", null, null));
        e.add(ev(e, "DELETE", "a", null));
        e.add(ev(e, "PUT", "c", "3"));
        e.add(ev(e, "COMMIT", null, null));
        return e;
    }

    private void appendToSegment(Path dir, long segId, byte[] data) throws Exception {
        Path seg = SegmentLog.segmentPath(dir, segId);
        try (FileChannel ch = FileChannel.open(seg, StandardOpenOption.WRITE,
                StandardOpenOption.READ)) {
            ch.position(ch.size());
            ch.write(ByteBuffer.wrap(data));
            ch.force(true);
        }
    }

    private String kvView(RecoveryResult r) {
        StringBuilder sb = new StringBuilder();
        r.kv().forEach((k, v) -> {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(k).append('=').append(v);
        });
        return sb.toString();
    }

    // 1. empty log
    @Test
    void emptyLogScansCleanAndRecoversNothing() throws Exception {
        Path dir = tmp.resolve("empty");
        Materializer.materialize(dir, List.of(), CrashSpec.none());
        // SEGHDR is 25-byte header + 16-byte payload = 41 bytes; body starts right after
        ScanOutcome scan = FrameScanner.scan(SegmentLog.segmentPath(dir, 1),
                Frame.HEADER_SIZE + 16);
        assertEquals(ScanStop.CLEAN_EOF, scan.stop());
        RecoveryResult r = Recovery.run(dir);
        assertEquals(TerminalStatus.CLEAN, r.status());
        assertTrue(r.kv().isEmpty());
        assertTrue(r.committedTxns().isEmpty());
    }

    // 2a. truncated header (0 < remaining < 29)
    @Test
    void truncatedHeaderIsClassified() throws Exception {
        Path dir = tmp.resolve("trunc-hdr");
        Materializer.materialize(dir, twoCommitted(), CrashSpec.none());
        byte[] raw = Frame.put(9, 1, "z", "9").encode();
        appendToSegment(dir, 1, java.util.Arrays.copyOf(raw, 10));
        RecoveryResult r = Recovery.run(dir);
        assertEquals(TerminalStatus.TRUNCATED_HEADER, r.status());
        assertEquals("b=2,c=3", kvView(r));
    }

    // 2b. truncated payload (full header, short payload)
    @Test
    void truncatedPayloadIsClassified() throws Exception {
        Path dir = tmp.resolve("trunc-payload");
        Materializer.materialize(dir, twoCommitted(), CrashSpec.none());
        byte[] raw = Frame.put(9, 1, "zzzzzzzz", "9").encode();
        // keep header + 4 payload bytes
        appendToSegment(dir, 1, java.util.Arrays.copyOf(raw, Frame.HEADER_SIZE + 4));
        RecoveryResult r = Recovery.run(dir);
        assertEquals(TerminalStatus.TRUNCATED_PAYLOAD, r.status());
        assertEquals("b=2,c=3", kvView(r));
    }

    // 3. bad crc
    @Test
    void bitFlipCausesBadCrcAndStopsScan() throws Exception {
        Path dir = tmp.resolve("bad-crc");
        Materializer.materialize(dir, twoCommitted(), CrashSpec.none());
        byte[] raw = Frame.put(9, 1, "flipme", "x").encode();
        raw[Frame.HEADER_SIZE] ^= 0x01; // payload byte -> CRC mismatch
        appendToSegment(dir, 1, raw);
        RecoveryResult r = Recovery.run(dir);
        assertEquals(TerminalStatus.BAD_CRC, r.status());
        assertEquals("b=2,c=3", kvView(r));
    }

    // 3b. unknown type
    @Test
    void unknownTypeIsClassified() throws Exception {
        Path dir = tmp.resolve("unknown");
        Materializer.materialize(dir, oneCommitted(), CrashSpec.none());
        byte[] raw = Frame.commit(9, 2).encode();
        raw[4] = 99; // type byte
        appendToSegment(dir, 1, raw);
        RecoveryResult r = Recovery.run(dir);
        assertEquals(TerminalStatus.UNKNOWN_TYPE, r.status());
        assertEquals("a=1", kvView(r));
    }

    // 4. uncommitted transaction discarded
    @Test
    void uncommittedTransactionIsDiscarded() throws Exception {
        Path dir = tmp.resolve("uncommitted");
        List<Event> events = new ArrayList<>();
        events.add(ev(events, "BEGIN", null, null));
        events.add(ev(events, "PUT", "a", "1"));
        events.add(ev(events, "COMMIT", null, null));
        events.add(ev(events, "BEGIN", null, null));
        events.add(ev(events, "PUT", "b", "2"));
        Materializer.materialize(dir, events, CrashSpec.afterEvent(events.get(4).id()));
        RecoveryResult r = Recovery.run(dir);
        assertEquals(TerminalStatus.CLEAN, r.status());
        assertEquals("a=1", kvView(r));
        assertTrue(r.discardedTxns().contains(2L));
        assertFalse(r.kv().containsKey("b"));
    }

    // 4b. gap in per-transaction sequence aborts the txn
    @Test
    void nonConsecutiveSeqAbortsTransaction() throws Exception {
        Path dir = tmp.resolve("seqgap");
        Materializer.materialize(dir, oneCommitted(), CrashSpec.none());
        byte[] begin = Frame.begin(7, 1).encode();
        byte[] commit = Frame.commit(7, 3).encode(); // missing seq 2
        appendToSegment(dir, 1, begin);
        appendToSegment(dir, 1, commit);
        RecoveryResult r = Recovery.run(dir);
        assertTrue(r.committedTxns().contains(1L));
        assertTrue(r.discardedTxns().contains(7L));
        assertEquals("a=1", kvView(r));
    }

    // 5. repeated recovery is idempotent, checkpointed ops never replayed
    @Test
    void repeatedRecoveryIsIdempotent() throws Exception {
        Path dir = tmp.resolve("repeat");
        List<Event> events = new ArrayList<>();
        events.add(ev(events, "BEGIN", null, null));
        events.add(ev(events, "PUT", "a", "1"));
        events.add(ev(events, "COMMIT", null, null));
        events.add(ev(events, "CHECKPOINT", null, null));
        Materializer.materialize(dir, events, CrashSpec.none());
        RecoveryResult first = Recovery.run(dir);
        RecoveryResult second = Recovery.run(dir);
        assertEquals("a=1", kvView(first));
        assertEquals(first.kv(), second.kv());
        assertTrue(first.snapshotUsed());
        assertTrue(second.snapshotUsed());
        assertEquals(2L, second.startSegmentId());
        assertFalse(Files.exists(SegmentLog.segmentPath(dir, 1)));
        assertTrue(Files.exists(SegmentLog.segmentPath(dir, 2)));
    }

    private List<Event> twoCheckpointEvents() {
        List<Event> e = new ArrayList<>();
        e.add(ev(e, "BEGIN", null, null));
        e.add(ev(e, "PUT", "a", "1"));
        e.add(ev(e, "COMMIT", null, null));
        e.add(ev(e, "CHECKPOINT", null, null)); // gen 1
        e.add(ev(e, "BEGIN", null, null));
        e.add(ev(e, "PUT", "b", "2"));
        e.add(ev(e, "COMMIT", null, null));
        e.add(ev(e, "CHECKPOINT", null, null)); // gen 2 (fault target)
        return e;
    }

    private RecoveryResult crashAtSecondCheckpoint(Path dir, String phase)
            throws Exception {
        List<Event> events = twoCheckpointEvents();
        Event target = events.get(7);
        List<Event> copy = new ArrayList<>();
        for (Event e : events) {
            if (e.id() == target.id()) {
                copy.add(new Event(e.id(), e.type(), e.txnId(), e.seq(), e.key(),
                        e.value(), new Fault(Fault.Kind.CP_INTERRUPT, 0, 0, 0, phase)));
            } else {
                copy.add(e);
            }
        }
        Materializer.materialize(dir, copy, CrashSpec.afterEvent(target.id()));
        return Recovery.run(dir);
    }

    // 6a. temp created: previous complete snapshot survives
    @Test
    void checkpointTempCreatedKeepsOldSnapshot() throws Exception {
        Path dir = tmp.resolve("cp1");
        RecoveryResult r = crashAtSecondCheckpoint(dir, "TEMP_CREATED");
        // old snapshot is untouched; segment 2 with committed txn 2 was already on disk,
        // so redo starts at segment 2 and reapplies b=2 from the continuous log
        assertEquals(1L, r.snapshotGeneration());
        assertEquals(2L, r.startSegmentId());
        assertEquals("a=1,b=2", kvView(r));
        assertTrue(Files.exists(Snapshot.tmpFile(dir)));
        assertTrue(Files.exists(Snapshot.file(dir)));
    }

    // 6b. temp synced: rename never happened
    @Test
    void checkpointTempSyncedKeepsOldSnapshot() throws Exception {
        Path dir = tmp.resolve("cp2");
        RecoveryResult r = crashAtSecondCheckpoint(dir, "TEMP_SYNCED");
        assertEquals(1L, r.snapshotGeneration());
        assertEquals(2L, r.startSegmentId());
        assertEquals("a=1,b=2", kvView(r));
    }

    // 6c. renamed: new snapshot is complete
    @Test
    void checkpointRenamedKeepsNewSnapshot() throws Exception {
        Path dir = tmp.resolve("cp3");
        RecoveryResult r = crashAtSecondCheckpoint(dir, "RENAMED");
        assertEquals(2L, r.snapshotGeneration());
        assertEquals("a=1,b=2", kvView(r));
    }

    // 7. segment gap
    @Test
    void segmentGapStopsRecoveryWithChainError() throws Exception {
        Path dir = tmp.resolve("seggap");
        List<Event> events = new ArrayList<>();
        events.add(ev(events, "BEGIN", null, null));
        events.add(ev(events, "PUT", "a", "1"));
        events.add(ev(events, "COMMIT", null, null));
        events.add(ev(events, "CHECKPOINT", null, null));
        events.add(ev(events, "BEGIN", null, null));
        events.add(ev(events, "PUT", "b", "2"));
        events.add(ev(events, "COMMIT", null, null));
        Materializer.materialize(dir, events, CrashSpec.none());
        Files.delete(SegmentLog.segmentPath(dir, 2));
        RecoveryResult r = Recovery.run(dir);
        assertEquals(TerminalStatus.START_SEGMENT_MISSING, r.status());
        assertEquals("a=1", kvView(r));
    }

    // 8. export -> empty instance -> identical recovery trace
    @Test
    void exportImportReplaysIdenticalTrace() throws Exception {
        Path storeDir = tmp.resolve("store");
        SandboxStore store = new SandboxStore(storeDir);
        var exp = store.create("导出测试");
        store.addEvent(exp.id(), "BEGIN", null, null, null);   // id 1
        store.addEvent(exp.id(), "PUT", "k", "0123456789", null); // id 2 (19B payload)
        store.addEvent(exp.id(), "COMMIT", null, null, null);  // id 3
        // short-write the PUT frame: header survives, 2 payload bytes are lost
        store.setFault(exp.id(), 2, Map.of("kind", "SHORT_WRITE", "bytes", 2));
        store.setCrash(exp.id(), CrashSpec.afterEvent(2));
        store.materialize(exp.id());
        RecoveryResult before = store.recover(exp.id());
        assertEquals(TerminalStatus.TRUNCATED_PAYLOAD, before.status());
        Path zip = store.exportZip(exp.id());

        SandboxStore store2 = new SandboxStore(tmp.resolve("store2"));
        String newId = store2.importZip(zip);
        RecoveryResult after = store2.recover(newId);

        assertEquals(before.status(), after.status());
        assertEquals(before.kv(), after.kv());
        assertEquals(before.steps().size(), after.steps().size());
        for (int i = 0; i < before.steps().size(); i++) {
            assertEquals(before.steps().get(i).title(), after.steps().get(i).title());
            assertEquals(before.steps().get(i).detail(), after.steps().get(i).detail());
            assertEquals(before.steps().get(i).kvAfter(), after.steps().get(i).kvAfter());
        }
    }

    // 9b. trailing garbage after a complete frame stops interpretation but keeps commits
    @Test
    void trailingGarbageStopsScanButKeepsCommittedData() throws Exception {
        Path dir = tmp.resolve("garbage");
        Materializer.materialize(dir, oneCommitted(), CrashSpec.none());
        byte[] garbage = new byte[20];
        new java.util.Random(42).nextBytes(garbage);
        appendToSegment(dir, 1, garbage);
        RecoveryResult r = Recovery.run(dir);
        assertNotEquals(TerminalStatus.CLEAN, r.status());
        assertEquals("a=1", kvView(r));
    }

    // 9c. byte-offset crash lands inside a write and truncates that frame
    @Test
    void crashAtByteOffsetTruncatesInsideFrame() throws Exception {
        Path dir = tmp.resolve("atbyte");
        List<Event> events = oneCommitted();
        // bytes: 41 seg header + 25 BEGIN + 35 PUT + 25 COMMIT; cut inside PUT payload
        Materializer.materialize(dir, events, CrashSpec.atByte(41 + 25 + 28));
        RecoveryResult r = Recovery.run(dir);
        assertEquals(TerminalStatus.TRUNCATED_PAYLOAD, r.status());
        assertTrue(r.kv().isEmpty());
        assertTrue(r.discardedTxns().contains(1L));
    }

    // 9d. faults only affect the selected write; resetting power yields clean data
    @Test
    void faultIsScopedAndResetProducesCleanLog() throws Exception {
        Path storeDir = tmp.resolve("isolation");
        SandboxStore store = new SandboxStore(storeDir);
        var exp = store.create("故障隔离");
        store.addEvent(exp.id(), "BEGIN", null, null, null);   // 1
        store.addEvent(exp.id(), "PUT", "a", "1", null);        // 2
        store.addEvent(exp.id(), "COMMIT", null, null, null);   // 3
        // bit flip attached to the PUT (event 2), crash after COMMIT (event 3)
        store.setFault(exp.id(), 2, Map.of("kind", "BIT_FLIP",
                "byteOffset", Frame.HEADER_SIZE, "bitIndex", 0));
        store.setCrash(exp.id(), CrashSpec.afterEvent(3));
        store.materialize(exp.id());
        RecoveryResult corrupted = store.recover(exp.id());
        assertEquals(TerminalStatus.BAD_CRC, corrupted.status());
        assertTrue(corrupted.kv().isEmpty());

        // power restored: faults and crash cleared, subsequent experiment is clean
        store.resetPower(exp.id());
        RecoveryResult clean = store.recover(exp.id());
        assertEquals(TerminalStatus.CLEAN, clean.status());
        assertEquals("a=1", kvView(clean));
    }

    // 9. orphan partial tmp snapshot is never read
    @Test
    void orphanTmpSnapshotIsIgnored() throws Exception {
        Path dir = tmp.resolve("orphan");
        Materializer.materialize(dir, oneCommitted(), CrashSpec.none());
        Files.writeString(Snapshot.tmpFile(dir), "garbage partial snapshot");
        RecoveryResult r = Recovery.run(dir);
        assertEquals(TerminalStatus.CLEAN, r.status());
        assertEquals("a=1", kvView(r));
    }
}
