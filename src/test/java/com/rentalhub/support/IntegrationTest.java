package com.rentalhub.support;

import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.transaction.TestTransaction;

import java.util.Objects;

/**
 * Base class for tests that boot the whole application against real Postgres and
 * Redis containers.
 *
 * All subclasses share one configuration, so Spring builds one application context
 * (and starts one pair of containers) for all of them. A subclass that added its own
 * annotations would get a context, and containers, of its own.
 *
 * After each test the tables (history tables included) and the caches are emptied, so
 * no test can see another's data. That matters for tests that are not
 * {@code @Transactional}: they really commit, because the behaviour under test
 * (after-commit cache eviction, audit history, what a real request sees) only happens
 * when something commits.
 *
 * The nightly stale-listing job is switched off ("-"): it runs only when a test calls it.
 */
@SpringBootTest(properties = "rentalhub.jobs.stale-listings.cron=-")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
public abstract class IntegrationTest {

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected CacheManager cacheManager;

    @AfterEach
    protected void resetDatabaseAndCaches() {
        // A @Transactional test is rolled back anyway, and may have left its
        // transaction unusable after a deliberate constraint violation.
        if (!TestTransaction.isActive()) {
            jdbc.execute("TRUNCATE TABLE bookings, favorites, reviews, property_images, properties, users, "
                    + "properties_aud, bookings_aud, reviews_aud, revinfo RESTART IDENTITY CASCADE");
        }
        // invalidate(), not clear(): clear() may finish after the next test has started.
        cacheManager.getCacheNames().forEach(name -> Objects.requireNonNull(cacheManager.getCache(name)).invalidate());
    }
}
