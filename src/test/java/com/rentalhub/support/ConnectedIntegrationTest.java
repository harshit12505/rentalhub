package com.rentalhub.support;

import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Objects;

/**
 * Base class for tests that need the optional services switched <em>on</em>.
 *
 * {@link IntegrationTest} runs the application as it starts with no credentials at all, which
 * is also the proof that it can. This one connects everything that is optional, without any
 * real account: fake AI models over the real pgvector store (FakeAiModels), and a MinIO
 * server standing in for S3 (MinioContainerConfiguration).
 *
 * All subclasses share this one configuration, and so one application context and one set of
 * containers, separate from IntegrationTest's. Clean-up is the same: every table truncated and
 * every cache emptied after each test.
 */
@SpringBootTest(properties = {
        "rentalhub.jobs.stale-listings.cron=-",
        "rentalhub.jobs.payment-reconciliation.cron=-",
        "rentalhub.ai.index-job.cron=-",
        "rentalhub.demo-data.enabled=false",
        // The models come from FakeAiModels; the vector store is the real one, switched back
        // on over the "no key, no AI" default (see AiEnvironmentPostProcessor).
        "spring.ai.vectorstore.type=pgvector"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, FixedExchangeRates.class, FakeAiModels.class,
        MinioContainerConfiguration.class})
public abstract class ConnectedIntegrationTest {

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected CacheManager cacheManager;

    @AfterEach
    protected void resetDatabaseAndCaches() {
        jdbc.execute("TRUNCATE TABLE bookings, favorites, reviews, property_images, properties, users, "
                + "properties_aud, bookings_aud, reviews_aud, revinfo, "
                + "vector_store, listing_embeddings RESTART IDENTITY CASCADE");
        cacheManager.getCacheNames().forEach(name -> Objects.requireNonNull(cacheManager.getCache(name)).invalidate());
    }
}
