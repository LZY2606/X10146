package wal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Scans a raw log byte stream into frames.
 *
 * <p>The scanner distinguishes the ways a log scan can end:
 * <ul>
 *   <li>{@link ScanOutcome#CLEAN_EOF} — the last byte on disk is the end of a frame;</li>
 *   <li>{@link ScanOutcome#TRUNCATED_HEADER} — fewer than a full frame header was durable;</li>
 *   <li>{@link ScanOutcome#TRUNCATED_PAYLOAD} — header is present but the payload is torn;</li>
 *   <li>{@link ScanOutcome#IMPLAUSIBLE_LENGTH} — the length field is garbage beyond the
 *       configured maximum frame size (tail garbage / a flipped length byte);</li>
 *   <li>{@link ScanOutcome#CHECKSUM_MISMATCH} — a complete frame that fails its checksum;</li>
 *   <li>{@link ScanOutcome#UNKNOWN_TYPE} — a complete frame with an unrecognised type byte.</li>
 * </ul>
 *
 * <p>Only a {@link ScanOutcome#VALID} record is applied during recovery. A complete
 * but invalid frame (bad crc / unknown type) consumes its bytes, but recovery stops
 * at it because the log beyond that point cannot be trusted to align.
 */
public final class LogScanner {

    /** Frames larger than this are treated as garbage rather than torn payloads. */
    public static final int MAX_PAYLOAD = 16 * 1024 * 1024;

    private LogScanner() {}

    public static List<ScanRecord> scan(byte[] data) {
        List<ScanRecord> records = new ArrayList<>();
        int offset = 0;
        while (true) {
            ScanRecord rec = nextRecord(data, offset);
            records.add(rec);
            if (rec.outcome == ScanOutcome.VALID
                    || rec.outcome == ScanOutcome.UNKNOWN_TYPE
                    || rec.outcome == ScanOutcome.CHECKSUM_MISMATCH) {
                offset = rec.offset + rec.consumed;
            }
            if (rec.outcome != ScanOutcome.VALID) {
                break;
            }
        }
        return records;
    }

    public static ScanRecord nextRecord(byte[] data, int offset) {
        int remaining = data.length - offset;
        if (remaining == 0) {
            return ScanRecord.eof(offset);
        }
        if (remaining < Frame.HEADER_SIZE) {
            return ScanRecord.error(ScanOutcome.TRUNCATED_HEADER, offset, -1);
        }
        ByteBuffer head = ByteBuffer.wrap(data, offset, Frame.HEADER_SIZE);
        int payloadLength = head.getInt();
        byte typeCode = head.get();
        long txnId = head.getLong();
        int seqNo = head.getInt();
        int storedCrc = head.getInt();

        if (payloadLength < 0 || payloadLength > MAX_PAYLOAD) {
            return ScanRecord.error(ScanOutcome.IMPLAUSIBLE_LENGTH, offset, payloadLength);
        }
        int total = Frame.HEADER_SIZE + payloadLength;
        if (remaining < total) {
            return ScanRecord.error(ScanOutcome.TRUNCATED_PAYLOAD, offset, payloadLength);
        }

        int crcOffset = offset + 4 + 1 + 8 + 4;
        int computed = Crc32c.crc32(data, offset, crcOffset - offset,
                data, offset + Frame.HEADER_SIZE, payloadLength);
        FrameType type = FrameType.fromCode(typeCode);
        if (computed != storedCrc) {
            return ScanRecord.error(ScanOutcome.CHECKSUM_MISMATCH, offset, payloadLength)
                    .withConsumed(total);
        }
        if (type == null) {
            return ScanRecord.error(ScanOutcome.UNKNOWN_TYPE, offset, payloadLength)
                    .withConsumed(total);
        }
        byte[] payload = new byte[payloadLength];
        System.arraycopy(data, offset + Frame.HEADER_SIZE, payload, 0, payloadLength);
        Frame frame = new Frame(type, txnId, seqNo, payload);
        return ScanRecord.valid(offset, total, frame);
    }

    public static List<ScanRecord> scanFile(Path file) throws IOException {
        byte[] data = Files.readAllBytes(file);
        return scan(data);
    }
}
