package walsandbox.sandbox;

import java.util.List;

public record Materialization(
        boolean crashed,
        String crashReason,
        int eventsApplied,
        long durableBytes,
        List<MaterializationEvent> writes) {

    public MaterializationEvent write(int eventId) {
        return writes.stream().filter(w -> w.eventId() == eventId).findFirst().orElse(null);
    }
}
