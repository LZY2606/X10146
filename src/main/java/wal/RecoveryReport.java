package wal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Full, UI-friendly explanation of a recovery pass. */
public final class RecoveryReport {
    public String selectedSnapshot;
    public long snapshotId = -1;
    public String snapshotDigest;
    public int baseSegment = -1;
    public final List<String> diagnostics = new ArrayList<>();
    public final List<String> segmentDecisions = new ArrayList<>();
    public final List<String> txnDecisions = new ArrayList<>();
    public final Map<String, String> kv = new LinkedHashMap<>();
    public int redoneTransactions;
    public int droppedTransactions;
    public int replayStopSegment = -1;
    public ScanOutcome replayStopReason;
    public boolean gap;
    public boolean repeatedRun;
}
