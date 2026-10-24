package com.dragonoro.pos;

/** Durable per-order dispatch journal. Stores no customer names, phones or addresses. */
public final class PrintJournal {
    public enum State { NEW, APPROVED, SENDING, SENT, SYNCED, UNCERTAIN, REVIEW }

    public interface Store {
        String read(long id);
        boolean write(long id, String value);
    }

    // Activity and Service create different journal instances in the same process.
    // They must still serialize read/modify/write transitions over shared preferences.
    private static final Object LOCK = new Object();
    private final Store store;
    public PrintJournal(Store store) { this.store = store; }

    public State state(long id) {
        synchronized (LOCK) {
            try {
                String raw = store.read(id);
                if (raw == null || raw.isEmpty()) return State.NEW;
                return State.valueOf(raw);
            } catch (RuntimeException error) {
                // Corrupt or future-format entries must never trigger blind reprinting.
                return State.UNCERTAIN;
            }
        }
    }

    public boolean begin(long id) {
        synchronized (LOCK) {
            State current = state(id);
            return id > 0 && (current == State.NEW || current == State.APPROVED)
                    && store.write(id, State.SENDING.name());
        }
    }

    public boolean sent(long id) {
        synchronized (LOCK) {
            return state(id) == State.SENDING && store.write(id, State.SENT.name());
        }
    }

    public boolean uncertain(long id) {
        synchronized (LOCK) { return store.write(id, State.UNCERTAIN.name()); }
    }

    public boolean synced(long id) {
        synchronized (LOCK) {
            return state(id) == State.SENT && store.write(id, State.SYNCED.name());
        }
    }

    public boolean review(long id) {
        synchronized (LOCK) {
            if (state(id) != State.NEW) return false;
            return store.write(id, State.REVIEW.name());
        }
    }

    /** Only after a native operator confirmation, not a remote JS instruction. */
    public boolean resolve(long id, boolean paperExists) {
        synchronized (LOCK) {
            State current = state(id);
            if (current != State.UNCERTAIN && current != State.REVIEW) return false;
            return store.write(id, paperExists ? State.SENT.name() : State.APPROVED.name());
        }
    }
}
