package com.rentalhub.cache;

import com.rentalhub.service.ListingBookedEvent;
import com.rentalhub.service.PropertyChangedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.util.ByteUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Keeps the caches consistent with the database: when a listing changes, removes it
 * from both tiers of the listing cache, and flushes the search pages that could
 * contain it.
 */
@Slf4j
@Component
class PropertyCacheInvalidator {

    private final CacheManager cacheManager;

    PropertyCacheInvalidator(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    /**
     * Runs only after the transaction that changed the listing has committed. If it ran
     * before, a request arriving in the gap could read the old row (still there, since
     * nothing had committed yet) and put it straight back into the cache, where it
     * would stay stale until it expired.
     *
     * Never throws. The change is already committed; an exception here would reach the
     * caller as an error for a request that actually succeeded. A failure (Redis down)
     * is logged, and the stale entries then expire by their TTL.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPropertyChanged(PropertyChangedEvent event) {
        Set<String> partitions = partitionsToFlush(event);
        try {
            // evictIfPresent, not evict: Spring's cache contract lets evict() happen
            // later, in the background; evictIfPresent() must be finished when it returns.
            requiredCache(CacheNames.PROPERTY_BY_ID).evictIfPresent(event.propertyId());
            Cache searchPages = requiredCache(CacheNames.PROPERTY_SEARCH);
            for (String pattern : partitions) {
                flush(searchPages, pattern);
            }
            log.info("cache.invalidated propertyId={} searchPartitions={}", event.propertyId(), partitions);
        } catch (RuntimeException e) {
            log.warn("cache.invalidation.failed propertyId={} searchPartitions={} error=\"{}\"",
                    event.propertyId(), partitions, e.getMessage());
        }
    }

    /**
     * A booking raised the listing's version, which the cached listing view shows. Only
     * that entry is evicted: search pages don't show the version, and flushing them on
     * every booking would throw away pages that are still correct.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onListingBooked(ListingBookedEvent event) {
        try {
            requiredCache(CacheNames.PROPERTY_BY_ID).evictIfPresent(event.propertyId());
            log.debug("cache.invalidated propertyId={} reason=booking", event.propertyId());
        } catch (RuntimeException e) {
            log.warn("cache.invalidation.failed propertyId={} reason=booking error=\"{}\"",
                    event.propertyId(), e.getMessage());
        }
    }

    /**
     * The only search pages a changed listing can appear on: its old city's, its new
     * city's, and those of searches with no city filter. Everything else stays cached.
     */
    static Set<String> partitionsToFlush(PropertyChangedEvent event) {
        Set<String> patterns = new LinkedHashSet<>();
        patterns.add(SearchCacheKeys.partitionPattern(null));
        if (event.previousCity() != null) {
            patterns.add(SearchCacheKeys.partitionPattern(event.previousCity()));
        }
        if (event.currentCity() != null) {
            patterns.add(SearchCacheKeys.partitionPattern(event.currentCity()));
        }
        return patterns;
    }

    /**
     * Deletes every key matching the pattern before returning.
     *
     * RedisCache offers clear(pattern), but that goes through the deferred path: the
     * keys may still be there for a moment afterwards, long enough for the next search
     * to be served a stale page. The cache writer's invalidate() is the immediate
     * version, so the pattern is built into a full Redis key (prefix included) and
     * handed to it directly.
     */
    private void flush(Cache cache, String pattern) {
        if (cache instanceof RedisCache redisCache) {
            RedisCacheConfiguration config = redisCache.getCacheConfiguration();
            String fullPattern = config.getKeyPrefixFor(redisCache.getName()) + pattern;
            byte[] rawPattern = ByteUtils.getBytes(config.getKeySerializationPair().write(fullPattern));
            redisCache.getNativeCache().invalidate(redisCache.getName(), rawPattern);
        } else {
            // A cache that cannot delete by pattern is emptied entirely: coarser, still correct.
            cache.invalidate();
        }
    }

    private Cache requiredCache(String name) {
        return Objects.requireNonNull(cacheManager.getCache(name), () -> "No cache named " + name);
    }
}
