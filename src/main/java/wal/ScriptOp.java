package wal;

/**
 * One editable action in an experiment script.
 *
 * Transactional ops (begin/put/delete/commit) use {@code txn} to select the
 * transaction and carry an auto-managed per-transaction sequence number.
 */
public final class ScriptOp {
    public enum Kind {
        BEGIN, PUT, DELETE, COMMIT, CHECKPOINT
    }

    public Kind kind;
    /** Transaction label used in the editor; mapped to a numeric id at materialisation. */
    public String txn;
    public String key;
    public String value;

    public ScriptOp() {}

    public ScriptOp(Kind kind, String txn, String key, String value) {
        this.kind = kind;
        this.txn = txn;
        this.key = key;
        this.value = value;
    }

    public static ScriptOp begin(String txn) {
        return new ScriptOp(Kind.BEGIN, txn, null, null);
    }

    public static ScriptOp put(String txn, String key, String value) {
        return new ScriptOp(Kind.PUT, txn, key, value);
    }

    public static ScriptOp delete(String txn, String key) {
        return new ScriptOp(Kind.DELETE, txn, key, null);
    }

    public static ScriptOp commit(String txn) {
        return new ScriptOp(Kind.COMMIT, txn, null, null);
    }

    public static ScriptOp checkpoint() {
        return new ScriptOp(Kind.CHECKPOINT, null, null, null);
    }

    @Override
    public String toString() {
        switch (kind) {
            case BEGIN: return "BEGIN " + txn;
            case PUT: return "PUT " + txn + " " + key + "=" + value;
            case DELETE: return "DELETE " + txn + " " + key;
            case COMMIT: return "COMMIT " + txn;
            case CHECKPOINT: return "CHECKPOINT";
            default: return kind.name();
        }
    }
}
