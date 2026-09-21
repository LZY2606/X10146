package walsandbox.frame;

public enum ScanStop {
    CLEAN_EOF("干净 EOF：扫描到日志精确结尾"),
    TRUNCATED_HEADER("截断帧：头部不完整（掉电时头部短写）"),
    TRUNCATED_PAYLOAD("截断帧：载荷不完整（掉电时载荷短写）"),
    BAD_CRC("校验失败：帧完整但 CRC 不匹配"),
    BAD_LENGTH("非法帧：声明长度超过上限"),
    UNKNOWN_TYPE("未知类型：帧类型码无法识别");

    private final String description;

    ScanStop(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}
