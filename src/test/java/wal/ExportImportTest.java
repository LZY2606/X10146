package wal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExportImportTest {

    @TempDir
    Path temp;

    @Test
    void exportedBytesReproduceTheSameRecoveryOnAnEmptyInstance() throws Exception {
        Path dataA = temp.resolve("instanceA");
        ExperimentStore storeA = new ExperimentStore(dataA);
        Experiment exp = storeA.create("可复现实验");
        exp.script.add(ScriptOp.begin("t1"));
        exp.script.add(ScriptOp.put("t1", "x", "42"));
        exp.script.add(ScriptOp.commit("t1"));
        exp.script.add(ScriptOp.checkpoint());
        exp.script.add(ScriptOp.begin("t2"));
        exp.script.add(ScriptOp.put("t2", "y", "7"));
        exp.plan.crash = true;
        exp.plan.anchor = CrashAnchor.AFTER_EVENT;
        exp.plan.eventIndex = 4; // crash after t2 PUT, before its commit
        storeA.save(exp);
        SimulationResult sim = storeA.materialise(exp.id);
        assertTrue(sim.crashed);
        RecoveryReport reportA = storeA.recover(exp.id);

        ByteArrayOutputStream zipBytes = new ByteArrayOutputStream();
        storeA.exportZip(exp.id, zipBytes);

        // brand new, empty instance
        Path dataB = temp.resolve("instanceB");
        ExperimentStore storeB = new ExperimentStore(dataB);
        Experiment imported = storeB.importZip(new ByteArrayInputStream(zipBytes.toByteArray()));
        RecoveryReport reportB = storeB.recover(imported.id);

        assertEquals(reportA.kv, reportB.kv);
        assertEquals(reportA.snapshotId, reportB.snapshotId);
        assertEquals(reportA.redoneTransactions, reportB.redoneTransactions);
        assertEquals(reportA.droppedTransactions, reportB.droppedTransactions);
        assertEquals(reportA.txnDecisions, reportB.txnDecisions);
        assertEquals("42", reportB.kv.get("x"));
        assertNull(reportB.kv.get("y"));

        // raw bytes are identical on both instances
        assertEquals(storeA.rawFiles(exp.id).keySet(),
                storeB.rawFiles(imported.id).keySet());
    }

    @Test
    void scannerClassifiesAllFourEndings() {
        // clean eof
        byte[] clean = Frame.begin(1, 1).encode();
        List<ScanRecord> scanned = LogScanner.scan(clean);
        assertEquals(ScanOutcome.VALID, scanned.get(0).outcome);
        assertEquals(ScanOutcome.CLEAN_EOF, scanned.get(1).outcome);

        // truncated header
        byte[] head = new byte[8];
        assertEquals(ScanOutcome.TRUNCATED_HEADER,
                LogScanner.nextRecord(head, 0).outcome);

        // truncated payload
        byte[] put = Frame.put(1, 2, "k", "v").encode();
        byte[] torn = new byte[Frame.HEADER_SIZE + 1];
        System.arraycopy(put, 0, torn, 0, torn.length);
        assertEquals(ScanOutcome.TRUNCATED_PAYLOAD,
                LogScanner.nextRecord(torn, 0).outcome);

        // checksum mismatch
        assertEquals(ScanOutcome.CHECKSUM_MISMATCH,
                LogScanner.nextRecord(Frame.commit(1, 3).encodeWithBadCrc(), 0).outcome);

        // unknown type with a valid crc
        byte[] u = Frame.begin(9, 1).encode();
        u[4] = 0x55;
        int crcOffset = 4 + 1 + 8 + 4;
        int crc = Crc32c.crc32(u, 0, crcOffset, u, Frame.HEADER_SIZE,
                u.length - Frame.HEADER_SIZE);
        java.nio.ByteBuffer.wrap(u, crcOffset, 4).putInt(crc);
        assertEquals(ScanOutcome.UNKNOWN_TYPE,
                LogScanner.nextRecord(u, 0).outcome);
    }
}
