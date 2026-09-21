package walsandbox.recover;

import java.util.Map;

/** One human-readable reasoning step in the recovery trace. */
public record EvidenceStep(
        int index,
        String kind,
        Long segmentId,
        Long offset,
        String title,
        String detail,
        Map<String, String> kvAfter) {
}
