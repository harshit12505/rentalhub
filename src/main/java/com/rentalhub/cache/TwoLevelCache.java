package com.rentalhub.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;

import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * A cache made of two caches: a small, very fast one inside this JVM (Caffeine) in
 * front of a shared one every instance can see (Redis).
 *
 * Reads try the local tier, then the shared tier, then load from the database, and
 * copy what they find into the tiers above. Writes and evictions go to both.
 *
 * The shared tier is an optimisation, never a dependency: if Redis fails, the
 * failure is logged and the read carries on as a miss, so the application keeps
 * working with local caching only.
 *
 * Known limitation, on purpose: the local tier belongs to one JVM. With several app
 * instances, evicting here clears this instance's local copy and the shared copy,
 * but another instance keeps serving its own local copy until it expires. That is
 * why the local TTL is short (30 seconds by default). RentalHub deploys as a single
 * instance, where this cannot happen. The multi-instance fix is to publish each
 * eviction on a Redis pub/sub channel that every instance listens to, evicting its
 * own local tier when a message arrives.
 */
public class TwoLevelCache implements Cache {

    private static final Logger log = LoggerFactory.getLogger(TwoLevelCache.class);

    private final Cache local;
    private final Cache shared;

    public TwoLevelCache(Cache local, Cache shared) {
        if (!local.getName().equals(shared.getName())) {
            throw new IllegalArgumentException(
                    "Both tiers must share one name: " + local.getName() + " vs " + shared.getName());
        }
        this.local = local;
        this.shared = shared;
    }

    @Override
    public String getName() {
        return local.getName();
    }

    @Override
    public Object getNativeCache() {
        return this;
    }

    public Cache localTier() {
        return local;
    }

    public Cache sharedTier() {
        return shared;
    }

    @Override
    public ValueWrapper get(Object key) {
        ValueWrapper localHit = local.get(key);
        if (localHit != null) {
            return localHit;
        }
        ValueWrapper sharedHit = safely("get", key, () -> shared.get(key));
        if (sharedHit != null) {
            local.put(key, sharedHit.get());
        }
        return sharedHit;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Object key, Class<T> type) {
        ValueWrapper hit = get(key);
        Object value = hit == null ? null : hit.get();
        if (value != null && type != null && !type.isInstance(value)) {
            throw new IllegalStateException("Cached value is not of required type [" + type.getName() + "]: " + value);
        }
        return (T) value;
    }

    /**
     * Used by {@code @Cacheable(sync = true)}. The local tier runs the loader at most
     * once per key at a time, so when many requests miss together only one of them
     * goes on to Redis and the database; the others wait for its result.
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Object key, Callable<T> valueLoader) {
        return local.get(key, () -> {
            ValueWrapper sharedHit = safely("get", key, () -> shared.get(key));
            if (sharedHit != null) {
                return (T) sharedHit.get();
            }
            T loaded = valueLoader.call();
            safely("put", key, () -> {
                shared.put(key, loaded);
                return null;
            });
            return loaded;
        });
    }

    @Override
    public void put(Object key, Object value) {
        local.put(key, value);
        safely("put", key, () -> {
            shared.put(key, value);
            return null;
        });
    }

    @Override
    public void evict(Object key) {
        local.evict(key);
        safely("evict", key, () -> {
            shared.evict(key);
            return null;
        });
    }

    @Override
    public void clear() {
        local.clear();
        safely("clear", "*", () -> {
            shared.clear();
            return null;
        });
    }

    /**
     * Spring's cache contract has two kinds of removal. evict() and clear() may be
     * carried out later, in the background. evictIfPresent() and invalidate() must be
     * complete when they return, so the very next read cannot see the old value.
     * Invalidation after a change needs the second kind, so both tiers get it.
     */
    @Override
    public boolean evictIfPresent(Object key) {
        boolean wasLocal = local.evictIfPresent(key);
        Boolean wasShared = safely("evictIfPresent", key, () -> shared.evictIfPresent(key));
        return wasLocal || Boolean.TRUE.equals(wasShared);
    }

    @Override
    public boolean invalidate() {
        boolean hadLocal = local.invalidate();
        Boolean hadShared = safely("invalidate", "*", shared::invalidate);
        return hadLocal || Boolean.TRUE.equals(hadShared);
    }

    private <R> R safely(String operation, Object key, Supplier<R> action) {
        try {
            return action.get();
        } catch (RuntimeException e) {
            log.atWarn().setMessage("cache.shared.unavailable")
                    .addKeyValue("cache", getName())
                    .addKeyValue("operation", operation)
                    .addKeyValue("key", key)
                    .addKeyValue("error", e.getMessage())
                    .log();
            return null;
        }
    }
}
