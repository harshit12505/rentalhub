package com.rentalhub.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.concurrent.ConcurrentMapCache;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.catchThrowable;

/** The two-tier logic on its own, with an in-memory map standing in for Redis. */
class TwoLevelCacheTest {

    private final CaffeineCache local = new CaffeineCache("listings", Caffeine.newBuilder().build(), false);
    private final ConcurrentMapCache shared = new ConcurrentMapCache("listings", false);
    private final TwoLevelCache cache = new TwoLevelCache(local, shared);

    @Test
    @DisplayName("a hit in the shared tier is copied into the local tier")
    void sharedHitIsPromoted() {
        shared.put(1L, "villa");

        assertThat(cache.get(1L)).isNotNull().extracting(Cache.ValueWrapper::get).isEqualTo("villa");
        assertThat(local.get(1L)).as("next read is local").isNotNull();
    }

    @Test
    @DisplayName("put and evict reach both tiers")
    void writesReachBothTiers() {
        cache.put(1L, "villa");
        assertThat(local.get(1L)).isNotNull();
        assertThat(shared.get(1L)).isNotNull();

        cache.evict(1L);
        assertThat(local.get(1L)).isNull();
        assertThat(shared.get(1L)).isNull();
    }

    @Test
    @DisplayName("the loader runs once, and its result lands in both tiers")
    void loaderRunsOnce() {
        AtomicInteger loads = new AtomicInteger();
        Callable<String> loader = () -> {
            loads.incrementAndGet();
            return "villa";
        };

        assertThat(cache.get(1L, loader)).isEqualTo("villa");
        assertThat(cache.get(1L, loader)).isEqualTo("villa");

        assertThat(loads).hasValue(1);
        assertThat(shared.get(1L)).isNotNull();
    }

    @Test
    @DisplayName("a failing loader caches nothing and its error reaches the caller")
    void loaderFailureIsNotCached() {
        Throwable thrown = catchThrowable(() -> cache.get(2L, () -> {
            throw new IllegalStateException("database down");
        }));

        assertThat(thrown).hasRootCauseInstanceOf(IllegalStateException.class);
        assertThat(local.get(2L)).isNull();
        assertThat(shared.get(2L)).isNull();
    }

    @Test
    @DisplayName("when the shared tier fails, the local tier keeps working")
    void sharedTierFailureDegradesToLocal() {
        TwoLevelCache degraded = new TwoLevelCache(local, new BrokenCache("listings"));

        assertThat(degraded.get(1L, () -> "villa")).isEqualTo("villa");
        assertThat(degraded.get(1L)).isNotNull().extracting(Cache.ValueWrapper::get).isEqualTo("villa");

        degraded.evict(1L);
        assertThat(local.get(1L)).isNull();
    }

    @Test
    @DisplayName("both tiers must carry the same name")
    void namesMustMatch() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TwoLevelCache(local, new ConcurrentMapCache("other")));
    }

    /** Stands in for Redis being unreachable: every operation throws. */
    private record BrokenCache(String getName) implements Cache {

        @Override
        public Object getNativeCache() {
            return this;
        }

        @Override
        public ValueWrapper get(Object key) {
            throw new IllegalStateException("redis down");
        }

        @Override
        public <T> T get(Object key, Class<T> type) {
            throw new IllegalStateException("redis down");
        }

        @Override
        public <T> T get(Object key, Callable<T> valueLoader) {
            throw new IllegalStateException("redis down");
        }

        @Override
        public void put(Object key, Object value) {
            throw new IllegalStateException("redis down");
        }

        @Override
        public void evict(Object key) {
            throw new IllegalStateException("redis down");
        }

        @Override
        public void clear() {
            throw new IllegalStateException("redis down");
        }
    }
}
