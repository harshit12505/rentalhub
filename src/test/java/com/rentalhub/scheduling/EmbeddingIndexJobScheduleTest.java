package com.rentalhub.scheduling;

import com.rentalhub.support.ConfiguredDefaults;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.CronExpression;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The backfill job's default schedule, as configured in application.yml.
 *
 * Often, and in small batches (rentalhub.ai.index-job.batch-size), because of Gemini's free
 * tier: a backlog is better cleared in frequent small bites than in one burst that would be
 * rejected halfway through.
 */
class EmbeddingIndexJobScheduleTest {

    @Test
    @DisplayName("by default it runs every 2 minutes")
    void everyTwoMinutes() {
        CronExpression cron = CronExpression.parse(ConfiguredDefaults.of("rentalhub.ai.index-job.cron"));

        assertThat(cron.next(LocalDateTime.of(2026, 9, 18, 12, 1, 30))).isEqualTo(LocalDateTime.of(2026, 9, 18, 12, 2));
        assertThat(cron.next(LocalDateTime.of(2026, 9, 18, 12, 2))).isEqualTo(LocalDateTime.of(2026, 9, 18, 12, 4));
    }

}
