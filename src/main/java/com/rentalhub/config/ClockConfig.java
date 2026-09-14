package com.rentalhub.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * The clock that decides what "today" is. A bean, rather than LocalDate.now() calls
 * scattered around, so rules that depend on the date can be tested with a fixed clock.
 *
 * It uses the JVM's time zone, the same one bean validation's {@code @FutureOrPresent}
 * uses, so the two never disagree about what today is. In the Render container that
 * zone is UTC.
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
