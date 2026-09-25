package me.chrommob.minestore.common.commandGetters;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Command queue ids this server has already taken from the store in this
 * session.
 *
 * The store keeps a row {@code pending = 1} until {@code commands/delivered}
 * succeeds and deletes it on {@code commands/executed}. When both of those
 * calls fail (the store answers 502 while it is being redeployed, which the
 * lobby logs show several times), the next poll hands out the same row again
 * and upstream ran it a second time: a rank or a Cevher grant delivered twice.
 * The ledger lets the listener recognise the repeat, acknowledge it again and
 * not run it.
 *
 * Bounded so a long uptime does not grow it without limit; the oldest ids are
 * the ones the store has long since deleted.
 */
public final class DeliveryLedger {
    public static final int DEFAULT_CAPACITY = 10_000;

    private final Map<Integer, Boolean> seen;

    public DeliveryLedger() {
        this(DEFAULT_CAPACITY);
    }

    public DeliveryLedger(final int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.seen = new LinkedHashMap<Integer, Boolean>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, Boolean> eldest) {
                return size() > capacity;
            }
        };
    }

    /**
     * Records the id.
     *
     * @return true the first time an id is seen, false for a repeat
     */
    public synchronized boolean firstTime(int id) {
        return seen.put(id, Boolean.TRUE) == null;
    }

    public synchronized boolean contains(int id) {
        return seen.containsKey(id);
    }

    public synchronized int size() {
        return seen.size();
    }
}
