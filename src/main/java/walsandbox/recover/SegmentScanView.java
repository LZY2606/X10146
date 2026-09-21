package walsandbox.recover;

import walsandbox.frame.ScanOutcome;

public record SegmentScanView(long segmentId, long prevSegmentId, String fileName,
                              long bodyStart, ScanOutcome outcome) {
}
