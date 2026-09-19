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
 * What the application would otherwise reach out to is pinned down:
 * <ul>
 *   <li>the demo data is switched off: each test starts from an empty database;</li>
 *   <li>every scheduled job is switched off ("-"): they run only when a test calls them;</li>
 *   <li>no Gemini key is set, so this context is also the proof the spec asks for: the whole
 *       application boots and works with the AI switched off. Tests that need the AI features
 *       use their own context with fake models (see ai/FakeAiModels);</li>
 *   <li>exchange rates are fixed (FixedExchangeRates), so no test depends on the network;</li>
 *   <li>no Stripe key is set, so payments go to the simulator, which answers to Stripe's
 *       test payment-method ids.</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "rentalhub.jobs.stale-listings.cron=-",
        "rentalhub.jobs.payment-reconciliation.cron=-",
        "rentalhub.ai.index-job.cron=-",
        // Every test builds the data it needs on an empty database (DemoDataSeederTest seeds on purpose).
        "rentalhub.demo-data.enabled=false",
        // Counts every SQL statement, so a test can prove how many queries something costs
        // (GraphQlApiTest: a page of search results with hosts and photos). The per-session
        // summary Hibernate would otherwise log after every request stays off.
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.jpa.properties.hibernate.session.events.log=false"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FixedExchangeRates.class})
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
                    + "properties_aud, bookings_aud, reviews_aud, revinfo, "
                    + "vector_store, listing_embeddings RESTART IDENTITY CASCADE");
        }
        // invalidate(), not clear(): clear() may finish after the next test has started.
        cacheManager.getCacheNames().forEach(name -> Objects.requireNonNull(cacheManager.getCache(name)).invalidate());
    }
}
