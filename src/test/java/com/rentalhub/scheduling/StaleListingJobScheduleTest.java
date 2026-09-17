package com.rentalhub.scheduling;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.support.CronExpression;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The schedule as configured in application.yml. The integration tests switch the job
 * off, so without this test a broken default cron would first be noticed in production.
 */
class StaleListingJobScheduleTest {

    /** ${STALE_LISTINGS_CRON:<default>}: the part after the first colon. */
    private static final Pattern PLACEHOLDER_DEFAULT = Pattern.compile("\\$\\{[^:}]+:(.*)}");

    @Test
    @DisplayName("the default schedule is a valid cron expression: every day at 03:15")
    void defaultScheduleIsDailyAt0315() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        String configured = Objects.requireNonNull(yaml.getObject()).getProperty("rentalhub.jobs.stale-listings.cron");

        Matcher placeholder = PLACEHOLDER_DEFAULT.matcher(configured);
        assertThat(placeholder.matches()).as("configured as %s", configured).isTrue();
        CronExpression cron = CronExpression.parse(placeholder.group(1));

        assertThat(cron.next(LocalDateTime.of(2026, 9, 14, 12, 0))).isEqualTo(LocalDateTime.of(2026, 9, 15, 3, 15));
        assertThat(cron.next(LocalDateTime.of(2026, 9, 15, 3, 15))).isEqualTo(LocalDateTime.of(2026, 9, 16, 3, 15));
    }
}
