package wal;

import java.util.zip.CRC32;

/** Checksum helper (JDK CRC-32) covering header bytes and payload. */
public final class Crc32c {
    private Crc32c() {}

    public static int crc32(byte[] head, int headOff, int headLen,
                            byte[] body, int bodyOff, int bodyLen) {
        CRC32 crc = new CRC32();
        crc.update(head, headOff, headLen);
        if (bodyLen > 0) {
            crc.update(body, bodyOff, bodyLen);
        }
        return (int) crc.getValue();
    }
}
