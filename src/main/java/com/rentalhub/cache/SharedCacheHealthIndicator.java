package com.rentalhub.cache;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

/**
 * Redis's part of {@code /actuator/health}: UP, or DEGRADED — never DOWN.
 *
 * Spring Boot's own Redis check reports DOWN when Redis can't be reached, and one DOWN part
 * makes the whole health check DOWN (HTTP 503). But Redis is only the shared cache here: without
 * it every read goes to Postgres and the app keeps working (phase 2). A hosting platform that
 * restarts or stops routing to an app whose health is DOWN would then take the whole site away
 * because an optional part is missing. So this replaces Boot's check (switched off in
 * application.yml) with one that says the truth: the site works, slower. DEGRADED is ordered
 * between DOWN and UP there, and answers HTTP 200.
 */
@Component("sharedCache")
public class SharedCacheHealthIndicator implements HealthIndicator {

    /** Working, without something it would normally use. */
    public static final Status DEGRADED = new Status("DEGRADED", "Redis is unreachable: every read goes to the database");

    private final RedisConnectionFactory redis;

    public SharedCacheHealthIndicator(RedisConnectionFactory redis) {
        this.redis = redis;
    }

    @Override
    public Health health() {
        try (RedisConnection connection = redis.getConnection()) {
            connection.ping();
            return Health.up().build();
        } catch (RuntimeException unreachable) {
            return Health.status(DEGRADED).withException(unreachable).build();
        }
    }
}
