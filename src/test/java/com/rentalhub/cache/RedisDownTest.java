package com.rentalhub.cache;

import com.rentalhub.domain.repository.UserRepository;
import com.rentalhub.dto.PropertyRequest;
import com.rentalhub.dto.SearchCriteria;
import com.rentalhub.service.PropertyService;
import com.rentalhub.service.SearchService;
import com.rentalhub.support.PostgresContainerConfiguration;
import com.rentalhub.support.TestRequests;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redis is an optimisation, not a dependency. This context has a real Postgres but
 * points Redis at a port where nothing listens, and everything must still work: reads
 * go to the database, listings are still cached locally, and writes still succeed.
 *
 * Its own application context (different configuration), so it has its own
 * throwaway database and needs no clean-up.
 */
@SpringBootTest(properties = {
        "spring.data.redis.url=redis://localhost:1",
        // Lettuce keeps retrying the dead port in the background; at shutdown Netty
        // complains that it can no longer schedule those retries. Harmless, just noisy.
        "logging.level.io.netty.util.concurrent.DefaultPromise.rejectedExecution=OFF"
})
@Import(PostgresContainerConfiguration.class)
class RedisDownTest {

    @Autowired
    private PropertyService propertyService;

    @Autowired
    private SearchService searchService;

    @Autowired
    private UserRepository users;

    @Autowired
    private CacheManager cacheManager;

    @Test
    @DisplayName("with Redis unreachable, reads and writes work and listings are still cached locally")
    void applicationWorksWithoutRedis() {
        long hostId = users.save(TestRequests.host()).getId();

        long id = propertyService.create(TestRequests.validVilla(), hostId).id();
        assertThat(propertyService.getListing(id).title()).isEqualTo("Test villa");
        assertThat(((TwoLevelCache) cacheManager.getCache(CacheNames.PROPERTY_BY_ID)).localTier().get(id))
                .as("the local tier still caches")
                .isNotNull();

        assertThat(searchService.search(new SearchCriteria("goa", null, null, null, 0, 20)).content()).hasSize(1);

        PropertyRequest change = TestRequests.validVilla();
        change.setTitle("Still works");
        propertyService.update(id, change, hostId);
        assertThat(propertyService.getListing(id).title()).isEqualTo("Still works");
    }
}
