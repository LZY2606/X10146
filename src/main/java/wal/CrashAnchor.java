package wal;

/** How the simulated power-loss point is chosen. */
public enum CrashAnchor {
    /** No crash: the whole script runs. */
    NONE,
    /** Power off after script event {@code eventIndex} (0-based, inclusive). */
    AFTER_EVENT,
    /** Power off leaving only {@code value} complete frames in the active segment. */
    AFTER_RECORDS,
    /** Power off leaving the active segment {@code value} bytes long. */
    AFTER_BYTES,
    /** Power off inside checkpoint event {@code eventIndex} at {@code stage}. */
    CKPT_PHASE
}
