package wal;

/** Known frame type codes. Any other byte is an unknown type. */
public enum FrameType {
    BEGIN((byte) 1),
    PUT((byte) 2),
    DELETE((byte) 3),
    COMMIT((byte) 4),
    CHECKPOINT((byte) 5);

    private final byte code;

    FrameType(byte code) {
        this.code = code;
    }

    public byte code() {
        return code;
    }

    public static FrameType fromCode(byte code) {
        for (FrameType t : values()) {
            if (t.code == code) return t;
        }
        return null;
    }
}
