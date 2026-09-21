package walsandbox.frame;

import java.util.Optional;

public enum FrameType {
    BEGIN(1),
    PUT(2),
    DELETE(3),
    COMMIT(4),
    CHECKPOINT(5),
    SEGHDR(6);

    private final int code;

    FrameType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static Optional<FrameType> fromCode(int code) {
        for (FrameType t : values()) {
            if (t.code == code) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }
}
