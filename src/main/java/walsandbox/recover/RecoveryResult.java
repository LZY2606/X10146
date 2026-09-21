package walsandbox.recover;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public record RecoveryResult(
        boolean snapshotUsed,
        long snapshotGeneration,
        long startSegmentId,
        TreeMap<String, String> kv,
        List<EvidenceStep> steps,
        List<SegmentScanView> scans,
        List<Long> committedTxns,
        List<Long> discardedTxns,
        TerminalStatus status,
        String terminalReason,
        int framesAccepted,
        int framesRejected) {

    public Map<String, String> kvView() {
        return kv;
    }
}
