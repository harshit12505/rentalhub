package com.rentalhub.scheduling;

import com.rentalhub.support.ConfiguredDefaults;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.CronExpression;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The schedule as configured in application.yml. The integration tests switch the job
 * off, so without this test a broken default cron would first be noticed in production.
 */
class StaleListingJobScheduleTest {

    @Test
    @DisplayName("the default schedule is a valid cron expression: every day at 03:15")
    void defaultScheduleIsDailyAt0315() {
        CronExpression cron = CronExpression.parse(ConfiguredDefaults.of("rentalhub.jobs.stale-listings.cron"));

        assertThat(cron.next(LocalDateTime.of(2026, 9, 14, 12, 0))).isEqualTo(LocalDateTime.of(2026, 9, 15, 3, 15));
        assertThat(cron.next(LocalDateTime.of(2026, 9, 15, 3, 15))).isEqualTo(LocalDateTime.of(2026, 9, 16, 3, 15));
    }
}
