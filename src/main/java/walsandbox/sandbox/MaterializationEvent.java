package walsandbox.sandbox;

public record MaterializationEvent(
        int eventId,
        long segmentId,
        long fileOffset,
        int fullLength,
        int writtenLength,
        String faultDescription) {
}
