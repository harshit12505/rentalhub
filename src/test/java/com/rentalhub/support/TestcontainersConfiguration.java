package com.rentalhub.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A real, throwaway PostgreSQL for tests that touch SQL, started in Docker.
 *
 * It is the same pgvector/pgvector:pg16 image docker-compose.yml runs, so Flyway,
 * the exclusion constraint and Hibernate's schema validation are tested against
 * exactly what production uses. H2 or another in-memory stand-in would not
 * understand btree_gist, daterange or pgvector at all.
 *
 * {@code @ServiceConnection} points Spring's datasource at the container
 * automatically. As a bean, the container is started once and shared by every test
 * class that imports this configuration, because Spring caches the test context.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(
                DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    }
}
