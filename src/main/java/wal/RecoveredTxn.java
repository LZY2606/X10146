package wal;

import java.util.ArrayList;
import java.util.List;

/** Replay bookkeeping for one transaction observed in the log. */
final class RecoveredTxn {
    final long id;
    int expectedSeq = 1;
    boolean begun;
    boolean committed;
    boolean rejected;
    String rejectReason;
    /** Recorded ops, applied at commit in sequence order. */
    final List<Frame> ops = new ArrayList<>();

    RecoveredTxn(long id) {
        this.id = id;
    }
}
