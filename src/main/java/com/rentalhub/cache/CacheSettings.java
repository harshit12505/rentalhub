package com.rentalhub.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Cache tuning, bound from {@code rentalhub.cache.*} in application.yml.
 *
 * @param keyPrefix           prepended to every Redis key. Contains a version: when the
 *                            shape of a cached record changes, bump it (v1 → v2) so the
 *                            new code never tries to read old-shaped JSON; old keys just
 *                            expire.
 * @param listingLocalTtl     how long a listing stays in this JVM's Caffeine cache. Short,
 *                            because it bounds how stale another instance's copy can get.
 * @param listingLocalMaxSize most listings kept in Caffeine; least-recently-used go first
 * @param listingSharedTtl    how long a listing stays in Redis. Longer: every change evicts it explicitly.
 * @param searchTtl           how long a search page stays in Redis
 */
@ConfigurationProperties("rentalhub.cache")
public record CacheSettings(
        @DefaultValue("rentalhub:v1:") String keyPrefix,
        @DefaultValue("30s") Duration listingLocalTtl,
        @DefaultValue("10000") long listingLocalMaxSize,
        @DefaultValue("10m") Duration listingSharedTtl,
        @DefaultValue("5m") Duration searchTtl) {
}
