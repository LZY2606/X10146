package walsandbox.frame;

import java.util.zip.CRC32;

public final class FrameCrc {

    private FrameCrc() {
    }

    /** CRC32 over [start,end) skipping the 4 crc bytes at {@code crcPos}. */
    public static int crc(byte[] data, int start, int end, int crcPos) {
        CRC32 crc = new CRC32();
        crc.update(data, start, crcPos - start);
        crc.update(data, crcPos + 4, end - crcPos - 4);
        return (int) crc.getValue();
    }

    public static int crc(byte[] data) {
        return crc(data, 0, data.length, data.length - 4);
    }
}
