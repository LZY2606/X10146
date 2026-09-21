package wal;

import java.nio.ByteBuffer;

/**
 * Header of a log segment file.
 *
 * <pre>
 *   int   magic = 0x57414C31 ("WAL1")
 *   int   segmentIndex   (the generation; monotonic across rotation)
 *   byte[16] zero padding
 * </pre>
 *
 * Recovery trusts the index embedded in the file, never the filename, so a
 * renamed or reordered segment can never silently change replay semantics.
 */
public final class SegmentHeader {
    public static final int MAGIC = 0x57414C31;
    public static final int SIZE = 4 + 4 + 16;

    private final int index;

    public SegmentHeader(int index) {
        this.index = index;
    }

    public int index() {
        return index;
    }

    public byte[] encode() {
        ByteBuffer buf = ByteBuffer.allocate(SIZE);
        buf.putInt(MAGIC);
        buf.putInt(index);
        return buf.array();
    }

    /** Returns null if the bytes are not a complete, valid segment header. */
    public static SegmentHeader decode(byte[] data) {
        if (data == null || data.length < SIZE) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.wrap(data, 0, SIZE);
        if (buf.getInt() != MAGIC) {
            return null;
        }
        return new SegmentHeader(buf.getInt());
    }

    public static boolean isPaddingZero(byte[] data) {
        for (int i = 8; i < SIZE; i++) {
            if (data[i] != 0) return false;
        }
        return true;
    }
}
