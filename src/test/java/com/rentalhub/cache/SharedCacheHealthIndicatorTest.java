package com.rentalhub.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Redis's health: UP when it answers, DEGRADED — never DOWN — when it doesn't. */
class SharedCacheHealthIndicatorTest {

    private final RedisConnectionFactory redis = mock(RedisConnectionFactory.class);
    private final SharedCacheHealthIndicator indicator = new SharedCacheHealthIndicator(redis);

    @Test
    @DisplayName("Redis answers a ping: UP")
    void up() {
        RedisConnection connection = mock(RedisConnection.class);
        when(connection.ping()).thenReturn("PONG");
        when(redis.getConnection()).thenReturn(connection);

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    @DisplayName("Redis can't be reached: DEGRADED, because the app still works without its shared cache")
    void degraded() {
        when(redis.getConnection()).thenThrow(new RedisConnectionFailureException("Connection refused"));

        assertThat(indicator.health().getStatus()).isEqualTo(SharedCacheHealthIndicator.DEGRADED);
        assertThat(indicator.health().getStatus()).isNotEqualTo(Status.DOWN);
    }
}
