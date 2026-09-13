package com.rentalhub.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A real, throwaway Redis, the same image docker-compose.yml runs. There is no
 * dedicated Testcontainers class for Redis; a generic container named "redis" is
 * enough for Spring Boot to point its Redis connection at it.
 */
@TestConfiguration(proxyBeanMethods = false)
public class RedisContainerConfiguration {

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redis() {
        return new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
    }
}
