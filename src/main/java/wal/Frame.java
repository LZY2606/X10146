package wal;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * A single write-ahead-log frame.
 *
 * Wire layout (all integers big endian):
 *   int   payloadLength
 *   byte  type          (FrameType code)
 *   long  txnId
 *   int   seqNo
 *   int  crc           (CRC32 over the 17 bytes preceding the crc plus the payload)
 *   byte[payloadLength] payload
 */
public final class Frame {
    public static final int HEADER_SIZE = 4 + 1 + 8 + 4 + 4;

    private final FrameType type;
    private final long txnId;
    private final int seqNo;
    private final byte[] payload;

    public Frame(FrameType type, long txnId, int seqNo, byte[] payload) {
        this.type = type;
        this.txnId = txnId;
        this.seqNo = seqNo;
        this.payload = payload == null ? new byte[0] : payload.clone();
    }

    public FrameType type() { return type; }
    public long txnId() { return txnId; }
    public int seqNo() { return seqNo; }
    public byte[] payload() { return payload.clone(); }

    public String payloadString() {
        return new String(payload, StandardCharsets.UTF_8);
    }

    public int totalSize() {
        return HEADER_SIZE + payload.length;
    }

    /** Encodes the frame, computing its checksum. */
    public byte[] encode() {
        ByteBuffer buf = ByteBuffer.allocate(totalSize());
        buf.putInt(payload.length);
        buf.put(type.code());
        buf.putLong(txnId);
        buf.putInt(seqNo);
        int crcPosition = buf.position();
        buf.putInt(0);
        buf.put(payload);
        int crc = Crc32c.crc32(buf.array(), 0, crcPosition, payload, 0, payload.length);
        buf.putInt(crcPosition, crc);
        return buf.array();
    }

    /** Encodes with a deliberately wrong checksum (bit-flip fault simulation). */
    public byte[] encodeWithBadCrc() {
        byte[] data = encode();
        int crcOffset = 4 + 1 + 8 + 4;
        data[crcOffset] ^= (byte) 0x80;
        return data;
    }

    public static Frame begin(long txnId, int seq) {
        return new Frame(FrameType.BEGIN, txnId, seq, new byte[0]);
    }

    public static Frame commit(long txnId, int seq) {
        return new Frame(FrameType.COMMIT, txnId, seq, new byte[0]);
    }

    public static Frame checkpoint(long txnId, int seq, long nextTxnId, int nextSegIndex,
                                   long snapshotId, String digest) {
        byte[] p = (nextTxnId + "," + nextSegIndex + "," + snapshotId + "," + digest)
                .getBytes(StandardCharsets.UTF_8);
        return new Frame(FrameType.CHECKPOINT, txnId, seq, p);
    }

    public static Frame put(long txnId, int seq, String key, String value) {
        return new Frame(FrameType.PUT, txnId, seq, kv(key, value));
    }

    public static Frame delete(long txnId, int seq, String key) {
        return new Frame(FrameType.DELETE, txnId, seq, key.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] kv(String key, String value) {
        byte[] k = key.getBytes(StandardCharsets.UTF_8);
        byte[] v = value.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(4 + k.length + 4 + v.length);
        buf.putInt(k.length);
        buf.put(k);
        buf.putInt(v.length);
        buf.put(v);
        return buf.array();
    }

    /** Decodes a PUT payload into [key, value]; returns null on malformed payload. */
    public String[] decodeKv() {
        ByteBuffer buf = ByteBuffer.wrap(payload);
        try {
            int klen = buf.getInt();
            if (klen < 0 || klen > buf.remaining()) return null;
            byte[] k = new byte[klen];
            buf.get(k);
            int vlen = buf.getInt();
            if (vlen < 0 || vlen != buf.remaining()) return null;
            byte[] v = new byte[vlen];
            buf.get(v);
            return new String[] {
                    new String(k, StandardCharsets.UTF_8),
                    new String(v, StandardCharsets.UTF_8)
            };
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Decodes a DELETE payload; returns null on malformed payload. */
    public String decodeKey() {
        return new String(payload, StandardCharsets.UTF_8);
    }
}
