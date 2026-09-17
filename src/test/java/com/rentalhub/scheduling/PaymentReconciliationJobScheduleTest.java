package com.rentalhub.scheduling;

import com.rentalhub.support.ConfiguredDefaults;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.scheduling.support.CronExpression;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** The reconciliation job's default schedule and patience, as configured in application.yml. */
class PaymentReconciliationJobScheduleTest {

    @Test
    @DisplayName("by default it runs every 5 minutes")
    void everyFiveMinutes() {
        CronExpression cron = CronExpression.parse(ConfiguredDefaults.of("rentalhub.jobs.payment-reconciliation.cron"));

        assertThat(cron.next(LocalDateTime.of(2026, 9, 15, 12, 1, 30))).isEqualTo(LocalDateTime.of(2026, 9, 15, 12, 5));
        assertThat(cron.next(LocalDateTime.of(2026, 9, 15, 12, 5))).isEqualTo(LocalDateTime.of(2026, 9, 15, 12, 10));
    }

    @Test
    @DisplayName("it leaves a payment alone for 10 minutes: far longer than a live one can take")
    void waitsLongerThanALivePayment() {
        Duration staleAfter = DurationStyle.detectAndParse(
                ConfiguredDefaults.of("rentalhub.jobs.payment-reconciliation.stale-after"));
        // Create, then confirm: each can wait out Stripe's 20 s read timeout, plus 2 retries.
        Duration longestLivePayment = Duration.ofSeconds(20).multipliedBy(3).multipliedBy(2);

        assertThat(staleAfter).isEqualTo(Duration.ofMinutes(10)).isGreaterThan(longestLivePayment);
    }
}
