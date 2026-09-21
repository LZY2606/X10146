package wal;

/**
 * Fault switches selectable for a single write in an experiment.
 * The fault applies only to the targeted write; every experiment is
 * materialised into a fresh directory, so faults never leak into later runs.
 */
public enum FaultKind {
    /** No corruption. */
    NONE,
    /** Only {@code param} bytes of the frame reach disk (torn frame). */
    SHORT_WRITE,
    /** {@code param} bytes of 0xFF tail garbage are written in place of the frame. */
    TRAIL_GARBAGE,
    /** The frame is complete but one crc bit is flipped. */
    BIT_FLIP,
    /** A complete frame with an unrecognised type byte is written. */
    UNKNOWN_TYPE,
    /** Checkpoint staged write interrupted at a chosen phase. */
    CKPT_INTERRUPT
}
