package wal;

/** Crash + fault configuration for an experiment. */
public final class CrashPlan {
    public boolean crash = false;
    public CrashAnchor anchor = CrashAnchor.NONE;
    /** Target event index (0-based) for AFTER_EVENT / CKPT_PHASE. */
    public int eventIndex = -1;
    /** Value for AFTER_RECORDS (frame count) or AFTER_BYTES (file length). */
    public int value = 0;
    public CheckpointStage stage = CheckpointStage.TEMP_SYNCED;

    public FaultKind fault = FaultKind.NONE;
    /** Script event whose write receives the fault. */
    public int faultTarget = -1;
    /** Fault parameter (short-write bytes / garbage length). */
    public int faultParam = 8;

    public static CrashPlan none() {
        return new CrashPlan();
    }
}
