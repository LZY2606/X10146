package walsandbox.sandbox;

/**
 * Power loss point:
 *   NONE                  run every append to completion (faults ignored)
 *   AFTER_EVENT(eventId)  durable prefix ends after the chosen append
 *   AT_BYTE(offset)       durable prefix ends at the first write crossing the byte count
 */
public record CrashSpec(Mode mode, int value) {

    public enum Mode {
        NONE, AFTER_EVENT, AT_BYTE
    }

    public static CrashSpec none() {
        return new CrashSpec(Mode.NONE, 0);
    }

    public static CrashSpec afterEvent(int eventId) {
        return new CrashSpec(Mode.AFTER_EVENT, eventId);
    }

    public static CrashSpec atByte(long offset) {
        return new CrashSpec(Mode.AT_BYTE, (int) Math.min(Integer.MAX_VALUE, offset));
    }
}
