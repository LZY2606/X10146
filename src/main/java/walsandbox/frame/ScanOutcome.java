package walsandbox.frame;

import java.util.List;

public record ScanOutcome(
        PathRef file,
        long startOffset,
        List<ScannedFrame> frames,
        ScanStop stop,
        long stopOffset,
        int expectedHeader,
        int availableHeader,
        int expectedPayload,
        int availablePayload,
        int claimedType,
        int claimedLength) {

    public record PathRef(java.nio.file.Path path) {
        @Override
        public String toString() {
            return path == null ? "" : path.toString();
        }
    }

    public boolean clean() {
        return stop == ScanStop.CLEAN_EOF;
    }

    public String stopExplanation() {
        return switch (stop) {
            case CLEAN_EOF -> "offset=" + stopOffset + " 处再无字节，日志正常结束";
            case TRUNCATED_HEADER -> "offset=" + stopOffset + " 处需要完整头部，仅剩 "
                    + availableHeader + " 字节";
            case TRUNCATED_PAYLOAD -> "offset=" + stopOffset + " 处帧声明载荷 "
                    + claimedLength + " 字节，实际只有 " + availablePayload + " 字节";
            case BAD_CRC -> "offset=" + stopOffset + " 处帧完整，但 CRC32 校验不通过";
            case BAD_LENGTH -> "offset=" + stopOffset + " 处声明载荷长度 " + claimedLength
                    + " 超过上限 " + Frame.MAX_PAYLOAD;
            case UNKNOWN_TYPE -> "offset=" + stopOffset + " 处类型码 " + claimedType + " 无法识别";
        };
    }
}
