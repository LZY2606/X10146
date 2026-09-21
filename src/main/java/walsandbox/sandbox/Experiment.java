package walsandbox.sandbox;

import java.util.ArrayList;
import java.util.List;

public final class Experiment {

    private final String id;
    private String name;
    private final List<Event> events = new ArrayList<>();
    private CrashSpec crash = CrashSpec.none();
    private int nextEventId = 1;
    private long nextTxnId = 1;
    private long activeTxnId;

    public Experiment(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<Event> events() {
        return events;
    }

    public CrashSpec crashSpec() {
        return crash;
    }

    public void setCrashSpec(CrashSpec spec) {
        this.crash = spec;
    }

    public long activeTxnId() {
        return activeTxnId;
    }

    public int nextEventId() {
        return nextEventId;
    }

    public long nextTxnId() {
        return nextTxnId;
    }

    public void restoreState(int nextEventId, long nextTxnId, long activeTxnId) {
        this.nextEventId = nextEventId;
        this.nextTxnId = nextTxnId;
        this.activeTxnId = activeTxnId;
    }

    /** Append a typed event, assigning txn/seq; returns the assigned event id. */
    public int addEvent(String type, String key, String value, Fault fault) {
        int eventId = nextEventId++;
        long txn;
        long seq;
        switch (type) {
            case "BEGIN" -> {
                txn = nextTxnId++;
                activeTxnId = txn;
                seq = 1;
            }
            case "PUT", "DELETE", "COMMIT" -> {
                if (activeTxnId == 0) {
                    nextEventId--;
                    throw new IllegalStateException("没有进行中的事务，先 BEGIN");
                }
                txn = activeTxnId;
                seq = lastSeq(txn) + 1;
                if (type.equals("COMMIT")) {
                    activeTxnId = 0;
                }
            }
            case "CHECKPOINT" -> {
                txn = 0;
                seq = 0;
            }
            default -> throw new IllegalArgumentException("未知记录类型 " + type);
        }
        if (fault == null) {
            fault = Fault.none();
        }
        events.add(new Event(eventId, type, txn, seq, key, value, fault));
        return eventId;
    }

    private long lastSeq(long txn) {
        long seq = 0;
        for (Event e : events) {
            if (e.txnId() != null && e.txnId() == txn) {
                seq = e.seq();
            }
        }
        return seq;
    }

    public Event findEvent(int eventId) {
        for (Event e : events) {
            if (e.id() == eventId) {
                return e;
            }
        }
        return null;
    }

    public void setFault(int eventId, Fault fault) {
        for (int i = 0; i < events.size(); i++) {
            Event e = events.get(i);
            if (e.id() == eventId) {
                events.set(i, new Event(e.id(), e.type(), e.txnId(), e.seq(), e.key(),
                        e.value(), fault == null ? Fault.none() : fault));
                return;
            }
        }
        throw new IllegalArgumentException("事件 #" + eventId + " 不存在");
    }

    public void clearFaults() {
        for (int i = 0; i < events.size(); i++) {
            events.set(i, events.get(i).withoutFault());
        }
    }

    public boolean transactionOpen() {
        return activeTxnId != 0;
    }
}
