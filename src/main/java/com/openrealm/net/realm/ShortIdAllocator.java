package com.openrealm.net.realm;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

// Short ID 0 is reserved as "no entity"; usable range is 1..0xFFFF, recycled on despawn.
public class ShortIdAllocator {
    private final AtomicInteger nextId = new AtomicInteger(1);

    private final Map<Long, Short> longToShort = new ConcurrentHashMap<>();
    private final Map<Short, Long> shortToLong = new ConcurrentHashMap<>();

    public short getOrAssign(long longId) {
        Short existing = longToShort.get(longId);
        if (existing != null) {
            return existing;
        }
        return assign(longId);
    }

    private synchronized short assign(long longId) {
        Short existing = longToShort.get(longId);
        if (existing != null) {
            return existing;
        }

        short shortId;
        int attempts = 0;
        do {
            int raw = nextId.getAndIncrement();
            // Wrap around, skip 0
            if (raw > 0xFFFF) {
                nextId.set(1);
                raw = nextId.getAndIncrement();
            }
            shortId = (short) (raw & 0xFFFF);
            attempts++;
            if (attempts > 0xFFFF) {
                throw new IllegalStateException("ShortIdAllocator exhausted: more than 65535 concurrent entities");
            }
        } while (shortId == 0 || shortToLong.containsKey(shortId));

        longToShort.put(longId, shortId);
        shortToLong.put(shortId, longId);
        return shortId;
    }

    public void release(long longId) {
        Short shortId = longToShort.remove(longId);
        if (shortId != null) {
            shortToLong.remove(shortId);
        }
    }

    /** Returns -1 if not found. */
    public long toLong(short shortId) {
        Long longId = shortToLong.get(shortId);
        return longId != null ? longId : -1L;
    }

    /** Returns 0 if not assigned. */
    public short toShort(long longId) {
        Short shortId = longToShort.get(longId);
        return shortId != null ? shortId : 0;
    }

    public boolean hasShortId(long longId) {
        return longToShort.containsKey(longId);
    }

    public int size() {
        return longToShort.size();
    }

    public void clear() {
        longToShort.clear();
        shortToLong.clear();
        nextId.set(1);
    }
}
