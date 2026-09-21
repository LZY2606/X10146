package walsandbox.frame;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class FrameScanner {

    private FrameScanner() {
    }

    /** Scan a segment from {@code startOffset} until EOF or the first broken frame. */
    public static ScanOutcome scan(Path file, long startOffset) throws IOException {
        byte[] all = Files.readAllBytes(file);
        return scanBytes(file, all, startOffset);
    }

    public static ScanOutcome scanBytes(Path file, byte[] all, long startOffset) {
        List<ScannedFrame> frames = new ArrayList<>();
        int pos = (int) startOffset;
        int n = all.length;
        while (true) {
            if (pos == n) {
                return outcome(file, startOffset, frames, ScanStop.CLEAN_EOF, pos,
                        0, 0, 0, 0, 0, 0);
            }
            int headerAvail = n - pos;
            if (headerAvail < Frame.HEADER_SIZE) {
                return outcome(file, startOffset, frames, ScanStop.TRUNCATED_HEADER, pos,
                        Frame.HEADER_SIZE, headerAvail, 0, 0, 0, 0);
            }
            ByteBuffer hdr = ByteBuffer.wrap(all, pos, Frame.HEADER_SIZE)
                    .order(ByteOrder.BIG_ENDIAN);
            int length = hdr.getInt();
            int typeCode = hdr.get() & 0xFF;
            long txnId = hdr.getLong();
            long seq = hdr.getLong();
            hdr.getInt(); // crc placeholder

            if (FrameType.fromCode(typeCode).isEmpty()) {
                return outcome(file, startOffset, frames, ScanStop.UNKNOWN_TYPE, pos,
                        Frame.HEADER_SIZE, Frame.HEADER_SIZE, length, 0, typeCode, length);
            }
            if (length < 0 || length > Frame.MAX_PAYLOAD) {
                return outcome(file, startOffset, frames, ScanStop.BAD_LENGTH, pos,
                        Frame.HEADER_SIZE, Frame.HEADER_SIZE, length, 0, typeCode, length);
            }
            int payloadAvail = n - pos - Frame.HEADER_SIZE;
            if (payloadAvail < length) {
                return outcome(file, startOffset, frames, ScanStop.TRUNCATED_PAYLOAD, pos,
                        Frame.HEADER_SIZE, Frame.HEADER_SIZE, length, payloadAvail, typeCode,
                        length);
            }
            byte[] raw = new byte[Frame.HEADER_SIZE + length];
            System.arraycopy(all, pos, raw, 0, raw.length);
            int storedCrc = ByteBuffer.wrap(raw, Frame.CRC_POSITION, 4)
                    .order(ByteOrder.BIG_ENDIAN).getInt();
            int actualCrc = FrameCrc.crc(raw, 0, raw.length,
                    Frame.CRC_POSITION);
            if (storedCrc != actualCrc) {
                return outcome(file, startOffset, frames, ScanStop.BAD_CRC, pos,
                        Frame.HEADER_SIZE, Frame.HEADER_SIZE, length, length, typeCode, length);
            }
            ByteBuffer payloadBuf = ByteBuffer.wrap(raw, Frame.HEADER_SIZE, length);
            byte[] payload = new byte[length];
            payloadBuf.get(payload);
            Frame frame = new Frame(FrameType.fromCode(typeCode).orElseThrow(), txnId, seq,
                    payload);
            frames.add(new ScannedFrame(frame, pos, raw));
            pos += raw.length;
        }
    }

    private static ScanOutcome outcome(Path file, long startOffset, List<ScannedFrame> frames,
                                       ScanStop stop, long stopOffset, int expectedHeader,
                                       int availableHeader, int expectedPayload,
                                       int availablePayload, int claimedType,
                                       int claimedLength) {
        return new ScanOutcome(new ScanOutcome.PathRef(file), startOffset,
                List.copyOf(frames), stop, stopOffset, expectedHeader, availableHeader,
                expectedPayload, availablePayload, claimedType, claimedLength);
    }
}
