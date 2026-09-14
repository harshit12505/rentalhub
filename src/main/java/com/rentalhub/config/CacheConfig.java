package com.rentalhub.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.rentalhub.cache.CacheNames;
import com.rentalhub.cache.CacheSettings;
import com.rentalhub.cache.TwoLevelCache;
import com.rentalhub.dto.PropertyView;
import com.rentalhub.dto.SearchResultPage;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.LoggingCacheErrorHandler;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.BatchStrategies;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * Wires the two caches RentalHub uses.
 *
 * <ul>
 *   <li><b>propertyById</b>: one listing's detail view. Two tiers: a Caffeine cache
 *       inside this JVM (nanosecond reads, short TTL) in front of Redis (shared by every
 *       instance, longer TTL). See {@link TwoLevelCache}, including its note on why the
 *       local tier can go stale when there is more than one instance.</li>
 *   <li><b>propertySearch</b>: pages of search results, in Redis only, keyed on the full
 *       normalised filter set. Search pages are numerous and each is only moderately hot,
 *       so sharing them across instances beats keeping per-instance copies.</li>
 * </ul>
 *
 * Values are cached as JSON records (DTOs), never as JPA entities.
 */
@Configuration(proxyBeanMethods = false)
@EnableCaching
@EnableConfigurationProperties(CacheSettings.class)
public class CacheConfig implements CachingConfigurer {

    @Bean
    public CacheManager cacheManager(RedisConnectionFactory redis, CacheSettings settings) {
        RedisCacheManager redisTier = redisCacheManager(redis, settings);

        CaffeineCache localListings = new CaffeineCache(
                CacheNames.PROPERTY_BY_ID,
                Caffeine.newBuilder()
                        .expireAfterWrite(settings.listingLocalTtl())
                        .maximumSize(settings.listingLocalMaxSize())
                        .build(),
                false);

        SimpleCacheManager manager = new SimpleCacheManager();
        manager.setCaches(List.of(
                new TwoLevelCache(localListings, redisTier.getCache(CacheNames.PROPERTY_BY_ID)),
                redisTier.getCache(CacheNames.PROPERTY_SEARCH)));
        return manager;
    }

    /**
     * If Redis fails during a cached call, log it and carry on as a miss: the method
     * runs and reads the database. Without this, a Redis outage would turn every
     * search into an error, although the database could answer perfectly well.
     */
    @Override
    public CacheErrorHandler errorHandler() {
        return new LoggingCacheErrorHandler(false);
    }

    private static RedisCacheManager redisCacheManager(RedisConnectionFactory redis, CacheSettings settings) {
        RedisCacheWriter writer = RedisCacheWriter.create(redis, writerConfig -> writerConfig
                // SCAN, not KEYS, when deleting by pattern. KEYS walks the whole keyspace in
                // one blocking call, freezing Redis for every other client while it runs;
                // SCAN does the same work in small batches.
                .batchStrategy(BatchStrategies.scan(1000))
                // With Lettuce, Spring Data Redis otherwise sends cache writes in the
                // background and returns at once. Waiting for them (about a millisecond)
                // guarantees the next request sees what this one cached.
                .immediateWrites());

        // Type-specific attributes travel as plain JSON numbers inside a Map<String, Object>.
        // Without this flag Jackson would read 450.00 back as the double 450.0.
        JsonMapper json = JsonMapper.builder()
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .build();

        RedisCacheConfiguration defaults = RedisCacheConfiguration.defaultCacheConfig()
                .computePrefixWith(cacheName -> settings.keyPrefix() + cacheName + "::")
                .disableCachingNullValues();

        RedisCacheManager manager = RedisCacheManager.builder(writer)
                .withCacheConfiguration(CacheNames.PROPERTY_BY_ID, defaults
                        .entryTtl(settings.listingSharedTtl())
                        .serializeValuesWith(SerializationPair.fromSerializer(
                                new JacksonJsonRedisSerializer<>(json, PropertyView.class))))
                .withCacheConfiguration(CacheNames.PROPERTY_SEARCH, defaults
                        .entryTtl(settings.searchTtl())
                        .serializeValuesWith(SerializationPair.fromSerializer(
                                new JacksonJsonRedisSerializer<>(json, SearchResultPage.class))))
                .disableCreateOnMissingCache()
                .build();
        manager.afterPropertiesSet();
        return manager;
    }
}
