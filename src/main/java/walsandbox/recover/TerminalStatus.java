package walsandbox.recover;

public enum TerminalStatus {
    CLEAN,
    TRUNCATED_HEADER,
    TRUNCATED_PAYLOAD,
    BAD_CRC,
    BAD_LENGTH,
    UNKNOWN_TYPE,
    START_SEGMENT_MISSING,
    CHAIN_BROKEN,
    NO_SNAPSHOT_CANDIDATE
}
