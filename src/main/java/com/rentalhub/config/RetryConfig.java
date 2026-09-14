package com.rentalhub.config;

import com.rentalhub.service.BookingSettings;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.retry.RetryListener;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryState;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.core.retry.Retryable;
import org.springframework.dao.ConcurrencyFailureException;

/**
 * Retry for bookings, built on Spring Framework 7's own retry support
 * ({@code org.springframework.core.retry}), the same engine its {@code @Retryable}
 * annotation uses. Spring Boot 4 no longer manages the separate Spring Retry library.
 *
 * A template called from code rather than an annotation, for two reasons:
 * <ol>
 *   <li>The optimistic-lock check fires as the transaction commits, so the retry must sit
 *       outside the transaction. In BookingService that nesting is plain to see (retry,
 *       then a transactional call, then recover) instead of depending on which of two
 *       proxies, the retry one or the transaction one, Spring happens to apply first.</li>
 *   <li>Framework 7 has no {@code @Recover}. When the retries run out a booking needs a
 *       specific answer ("those dates were just taken"), and a try/catch around the
 *       template gives exactly that. It can also be unit-tested without Spring.</li>
 * </ol>
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BookingSettings.class)
public class RetryConfig {

    public static final String BOOKING_RETRY = "bookingRetry";

    @Bean(BOOKING_RETRY)
    public RetryTemplate bookingRetry(BookingSettings settings) {
        return bookingRetryTemplate(settings.retry());
    }

    /** Public so unit tests can build the same policy with zero delays. */
    public static RetryTemplate bookingRetryTemplate(BookingSettings.Retry settings) {
        RetryPolicy policy = RetryPolicy.builder()
                // Retry only failures caused by another transaction working on the same rows
                // at the same moment. ConcurrencyFailureException covers both kinds we meet:
                //  - a lost version race (optimistic locking): the booking that won may have
                //    been for other dates, so trying again can succeed;
                //  - a deadlock: two bookings inserting overlapping dates at the same instant
                //    can each wait for the other inside the exclusion constraint, and Postgres
                //    cancels one of them. Its retry sees the survivor's booking and answers
                //    accordingly.
                // Anything else (dates taken, a rule broken) would fail the same way every time.
                .includes(ConcurrencyFailureException.class)
                .maxRetries(settings.maxRetries())
                .delay(settings.delay())
                .multiplier(settings.multiplier())
                .jitter(settings.jitter())
                .maxDelay(settings.maxDelay())
                .build();
        RetryTemplate template = new RetryTemplate(policy);
        template.setRetryListener(new LoggingRetryListener("booking"));
        return template;
    }

    /** One log line per retry, so contention on a listing is visible in the logs. */
    private record LoggingRetryListener(String operation) implements RetryListener {

        @Override
        public void beforeRetry(RetryPolicy policy, Retryable<?> retryable, RetryState state) {
            Throwable cause = state.getLastException();
            log.info("retry.attempt operation={} retry={} cause={}", operation, state.getRetryCount(),
                    cause == null ? "unknown" : cause.getClass().getSimpleName());
        }
    }
}
