package wal;

/** Result of attempting to interpret one frame boundary while scanning a byte stream. */
public enum ScanOutcome {
    /** A complete, checksum-valid frame with a known type. */
    VALID,
    /** A complete frame whose type byte is not recognised. */
    UNKNOWN_TYPE,
    /** A complete frame whose checksum does not match. */
    CHECKSUM_MISMATCH,
    /** Fewer than 21 header bytes remain. */
    TRUNCATED_HEADER,
    /** Header present but the declared payload is not fully on disk. */
    TRUNCATED_PAYLOAD,
    /** The declared payload length is implausible (tail garbage / corruption). */
    IMPLAUSIBLE_LENGTH,
    /** Zero bytes remain: a clean end of log. */
    CLEAN_EOF
}
