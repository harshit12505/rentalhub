package com.rentalhub.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Switches on {@code @Scheduled} methods (see the scheduling package). Spring Boot then
 * provides the scheduler: one thread by default ({@code spring.task.scheduling.*}),
 * which is plenty for one nightly job.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SchedulingConfig {
}
