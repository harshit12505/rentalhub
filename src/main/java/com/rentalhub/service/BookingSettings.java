package com.rentalhub.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Booking rules and retry tuning, bound from {@code rentalhub.booking.*} in application.yml.
 *
 * @param maxNights the longest stay one booking may cover. Stops a single request from
 *                  blocking a listing's calendar for years.
 * @param retry     how an attempt that lost a race for its listing is retried (see RetryConfig)
 */
@ConfigurationProperties("rentalhub.booking")
public record BookingSettings(
        @DefaultValue("90") int maxNights,
        @DefaultValue Retry retry) {

    /**
     * Exponential backoff with jitter: wait {@code delay}, then {@code delay × multiplier},
     * then {@code delay × multiplier²} ..., never more than {@code maxDelay}, each wait
     * shifted by a random amount up to {@code jitter}.
     *
     * @param maxRetries retries after the first attempt, so 3 means at most 4 attempts
     * @param delay      wait before the first retry. Short: the booking that beat us has
     *                   already committed, so the listing is free to try again at once
     * @param multiplier how much longer each following wait is
     * @param jitter     the random shift. Without it, two requests that collided once
     *                   would wait exactly as long as each other and collide again
     * @param maxDelay   the longest any single wait may be
     */
    public record Retry(
            @DefaultValue("3") long maxRetries,
            @DefaultValue("50ms") Duration delay,
            @DefaultValue("2") double multiplier,
            @DefaultValue("25ms") Duration jitter,
            @DefaultValue("500ms") Duration maxDelay) {
    }
}
