package walsandbox.frame;

import java.util.Arrays;

public record ScannedFrame(Frame frame, long offset, byte[] raw) {

    public int totalLength() {
        return raw.length;
    }

    public long endOffset() {
        return offset + raw.length;
    }

    @Override
    public String toString() {
        return "@" + offset + " " + frame.describe();
    }
}
