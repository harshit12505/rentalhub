package com.rentalhub.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;

/** Everything the application talks to, as throwaway containers: Postgres and Redis. */
@TestConfiguration(proxyBeanMethods = false)
@Import({PostgresContainerConfiguration.class, RedisContainerConfiguration.class})
public class TestcontainersConfiguration {
}
