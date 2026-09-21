package walsandbox.sandbox;

public record Event(
        int id,
        String type,        // BEGIN, PUT, DELETE, COMMIT, CHECKPOINT
        Long txnId,         // assigned server side for BEGIN
        Long seq,           // assigned server side
        String key,
        String value,
        Fault fault) {

    public Event withIds(long txn, long sequence) {
        return new Event(id, type, txn, sequence, key, value, fault);
    }

    public Event withoutFault() {
        return new Event(id, type, txnId, seq, key, value, Fault.none());
    }
}
