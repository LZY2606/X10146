package walsandbox.sandbox;

/**
 * A fault attached to one specific append. Faults only take effect when materializing
 * a crash; clean runs and later experiments are untouched.
 *
 *   SHORT_WRITE  cut trailing bytes off this frame's write ({@code bytes} may be
 *                negative in UI; stored as a positive amount)
 *   GARBAGE      append {@code bytes} random trailing bytes after the frame
 *   BIT_FLIP     flip one bit of byte at frame-local offset {@code byteOffset}
 *   CP_INTERRUPT checkpoint durability protocol crash (see Snapshot.CrashPhase)
 */
public record Fault(Kind kind, int bytes, int byteOffset, int bitIndex, String cpPhase) {

    public enum Kind {
        NONE, SHORT_WRITE, GARBAGE, BIT_FLIP, CP_INTERRUPT
    }

    public static Fault none() {
        return new Fault(Kind.NONE, 0, 0, 0, null);
    }

    public boolean active() {
        return kind != Kind.NONE;
    }
}
