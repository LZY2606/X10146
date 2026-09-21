package wal;

/** One scanner event: an outcome at a byte offset, carrying the decoded frame when valid. */
public final class ScanRecord {
    public final ScanOutcome outcome;
    public final int offset;
    public final int consumed;
    public final Frame frame;
    public final int declaredPayloadLength;

    private ScanRecord(ScanOutcome outcome, int offset, int consumed, Frame frame,
                       int declaredPayloadLength) {
        this.outcome = outcome;
        this.offset = offset;
        this.consumed = consumed;
        this.frame = frame;
        this.declaredPayloadLength = declaredPayloadLength;
    }

    public static ScanRecord valid(int offset, int consumed, Frame frame) {
        return new ScanRecord(ScanOutcome.VALID, offset, consumed, frame, frame.totalSize());
    }

    public static ScanRecord error(ScanOutcome outcome, int offset, int declaredLength) {
        return new ScanRecord(outcome, offset, 0, null, declaredLength);
    }

    public ScanRecord withConsumed(int consumedBytes) {
        return new ScanRecord(outcome, offset, consumedBytes, frame, declaredPayloadLength);
    }

    public static ScanRecord eof(int offset) {
        return new ScanRecord(ScanOutcome.CLEAN_EOF, offset, 0, null, 0);
    }

    public boolean terminal() {
        return outcome != ScanOutcome.VALID && outcome != ScanOutcome.UNKNOWN_TYPE;
    }

    @Override
    public String toString() {
        return outcome + "@" + offset;
    }
}
