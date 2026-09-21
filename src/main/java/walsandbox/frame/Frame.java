package walsandbox.frame;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Binary frame layout (big endian), 29-byte header + payload:
 *
 *   uint32 length          payload length
 *   uint8  type            FrameType code
 *   uint64 txnId           transaction id (0 for non-transactional)
 *   uint64 seq             per-transaction sequence number (starts at 1)
 *   uint32 crc             CRC32 over every byte except the crc field itself
 *
 * Payloads:
 *   BEGIN/COMMIT : empty
 *   PUT          uint32 keyLen, key bytes, uint32 valLen, value bytes
 *   DELETE       uint32 keyLen, key bytes
 *   CHECKPOINT   uint64 generation, uint64 startSegmentId
 *   SEGHDR       uint64 segmentId, uint64 prevSegmentId
 */
public final class Frame {

    public static final int HEADER_SIZE = 25;
    public static final int CRC_POSITION = 21;
    public static final int MAX_PAYLOAD = 1 << 20;

    private final FrameType type;
    private final long txnId;
    private final long seq;
    private final byte[] payload;

    public Frame(FrameType type, long txnId, long seq, byte[] payload) {
        this.type = type;
        this.txnId = txnId;
        this.seq = seq;
        this.payload = payload == null ? new byte[0] : payload;
    }

    public FrameType type() {
        return type;
    }

    public long txnId() {
        return txnId;
    }

    public long seq() {
        return seq;
    }

    public byte[] payload() {
        return payload;
    }

    public static Frame begin(long txnId, long seq) {
        return new Frame(FrameType.BEGIN, txnId, seq, new byte[0]);
    }

    public static Frame put(long txnId, long seq, String key, String value) {
        byte[] kb = key.getBytes(StandardCharsets.UTF_8);
        byte[] vb = value.getBytes(StandardCharsets.UTF_8);
        ByteBuffer b = ByteBuffer.allocate(8 + kb.length + vb.length);
        putBytes(b, kb);
        putBytes(b, vb);
        return new Frame(FrameType.PUT, txnId, seq, b.array());
    }

    public static Frame delete(long txnId, long seq, String key) {
        byte[] kb = key.getBytes(StandardCharsets.UTF_8);
        ByteBuffer b = ByteBuffer.allocate(4 + kb.length);
        putBytes(b, kb);
        return new Frame(FrameType.DELETE, txnId, seq, b.array());
    }

    public static Frame commit(long txnId, long seq) {
        return new Frame(FrameType.COMMIT, txnId, seq, new byte[0]);
    }

    public static Frame checkpoint(long generation, long startSegmentId) {
        ByteBuffer b = ByteBuffer.allocate(16)
                .order(ByteOrder.BIG_ENDIAN)
                .putLong(generation)
                .putLong(startSegmentId);
        return new Frame(FrameType.CHECKPOINT, 0, 0, b.array());
    }

    public static Frame segHdr(long segmentId, long prevSegmentId) {
        ByteBuffer b = ByteBuffer.allocate(16)
                .order(ByteOrder.BIG_ENDIAN)
                .putLong(segmentId)
                .putLong(prevSegmentId);
        return new Frame(FrameType.SEGHDR, 0, 0, b.array());
    }

    public KeyValue putPayload() {
        ByteBuffer b = wrap(payload);
        byte[] key = getBytes(b);
        byte[] value = getBytes(b);
        return new KeyValue(new String(key, StandardCharsets.UTF_8),
                new String(value, StandardCharsets.UTF_8));
    }

    public String deletePayload() {
        return new String(getBytes(wrap(payload)), StandardCharsets.UTF_8);
    }

    public long[] checkpointPayload() {
        ByteBuffer b = wrap(payload);
        return new long[] {b.getLong(), b.getLong()};
    }

    public long[] segHdrPayload() {
        return checkpointPayload();
    }

    public byte[] encode() {
        ByteBuffer out = ByteBuffer.allocate(HEADER_SIZE + payload.length)
                .order(ByteOrder.BIG_ENDIAN);
        out.putInt(payload.length);
        out.put((byte) type.code());
        out.putLong(txnId);
        out.putLong(seq);
        int crcPos = Frame.CRC_POSITION;
        out.putInt(0);
        out.put(payload);
        int crc = FrameCrc.crc(out.array(), 0, HEADER_SIZE + payload.length, crcPos);
        out.putInt(crcPos, crc);
        return out.array();
    }

    public record KeyValue(String key, String value) {
    }

    private static ByteBuffer wrap(byte[] data) {
        return ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
    }

    private static void putBytes(ByteBuffer b, byte[] data) {
        b.putInt(data.length);
        b.put(data);
    }

    private static byte[] getBytes(ByteBuffer b) {
        int len = b.getInt();
        byte[] data = new byte[len];
        b.get(data);
        return data;
    }

    public String describe() {
        return switch (type) {
            case BEGIN -> "BEGIN txn=" + txnId + " seq=" + seq;
            case COMMIT -> "COMMIT txn=" + txnId + " seq=" + seq;
            case PUT -> {
                KeyValue kv = putPayload();
                yield "PUT txn=" + txnId + " seq=" + seq + " " + kv.key() + "=" + kv.value();
            }
            case DELETE -> "DELETE txn=" + txnId + " seq=" + seq + " " + deletePayload();
            case CHECKPOINT -> {
                long[] cp = checkpointPayload();
                yield "CHECKPOINT gen=" + cp[0] + " startSegment=" + cp[1];
            }
            case SEGHDR -> {
                long[] sh = segHdrPayload();
                yield "SEGHDR segment=" + sh[0] + " prev=" + sh[1];
            }
        };
    }

    @Override
    public String toString() {
        return "Frame{" + describe() + ", payload=" + payload.length + "B}";
    }
}
