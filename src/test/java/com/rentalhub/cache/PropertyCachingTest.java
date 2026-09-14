package com.rentalhub.cache;

import com.rentalhub.domain.model.User;
import com.rentalhub.domain.repository.PropertyRepository;
import com.rentalhub.domain.repository.UserRepository;
import com.rentalhub.dto.PropertyRequest;
import com.rentalhub.dto.PropertyView;
import com.rentalhub.dto.SearchCriteria;
import com.rentalhub.exception.ResourceNotFoundException;
import com.rentalhub.factory.PropertyFactory;
import com.rentalhub.service.PropertyService;
import com.rentalhub.service.SearchService;
import com.rentalhub.support.IntegrationTest;
import com.rentalhub.support.TestRequests;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The caches, end to end, against real Postgres and Redis.
 *
 * Deliberately not {@code @Transactional}: eviction happens after a commit, so these
 * tests must really commit. IntegrationTest empties tables and caches after each one.
 *
 * To prove an answer came from a cache rather than the database, several tests change
 * the database behind the service's back (plain SQL, which publishes no event and so
 * evicts nothing). If the service still returns the old answer, only a cache could
 * have supplied it.
 */
class PropertyCachingTest extends IntegrationTest {

    @Autowired
    private PropertyService propertyService;

    @Autowired
    private SearchService searchService;

    @Autowired
    private PropertyFactory factory;

    @Autowired
    private PropertyRepository properties;

    @Autowired
    private UserRepository users;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private CacheSettings settings;

    private User host;

    @BeforeEach
    void createHost() {
        host = users.save(TestRequests.host());
    }

    // ------------------------------------------------------------ listing cache

    @Test
    @DisplayName("a listing read once is then served from the cache, and sits in both tiers")
    void listingIsCachedInBothTiers() {
        long id = propertyService.create(TestRequests.validVilla(), host.getId()).id();
        PropertyView first = propertyService.getListing(id);

        deleteRowBehindTheServicesBack(id);

        assertThat(propertyService.getListing(id)).isEqualTo(first);
        assertThat(localTier().get(id)).isNotNull();
        assertThat(redis.hasKey(listingKey(id))).isTrue();
    }

    @Test
    @DisplayName("a listing read back from Redis is identical to the one cached, BigDecimals included")
    void redisRoundTripIsLossless() {
        long id = propertyService.create(TestRequests.validVilla(), host.getId()).id();
        PropertyView original = propertyService.getListing(id);

        localTier().evict(id);                // forget the local copy...
        deleteRowBehindTheServicesBack(id);   // ...and the database row: only Redis is left

        PropertyView fromRedis = propertyService.getListing(id);
        assertThat(fromRedis).isEqualTo(original);
        assertThat(fromRedis.attributes().get("plotAreaSqm")).isInstanceOf(BigDecimal.class);
    }

    @Test
    @DisplayName("updating a listing evicts it from both tiers")
    void updateEvictsBothTiers() {
        long id = propertyService.create(TestRequests.validVilla(), host.getId()).id();
        propertyService.getListing(id);

        PropertyRequest change = TestRequests.validVilla();
        change.setTitle("Renamed villa");
        propertyService.update(id, change, host.getId());

        assertThat(localTier().get(id)).isNull();
        assertThat(redis.hasKey(listingKey(id))).isFalse();
        assertThat(propertyService.getListing(id).title()).isEqualTo("Renamed villa");
    }

    @Test
    @DisplayName("deleting a listing evicts it, so it is gone at once")
    void deleteEvicts() {
        long id = propertyService.create(TestRequests.validVilla(), host.getId()).id();
        propertyService.getListing(id);

        propertyService.delete(id, host.getId());

        assertThatThrownBy(() -> propertyService.getListing(id)).isInstanceOf(ResourceNotFoundException.class);
    }

    // ------------------------------------------------------------- search cache

    @Test
    @DisplayName("a search page is cached in Redis under its normalised filters, with a TTL")
    void searchPageIsCached() {
        propertyService.create(TestRequests.validVilla(), host.getId());
        SearchCriteria messy = new SearchCriteria("  GOA ", null, null, 0, 20);

        assertThat(searchService.search(messy).content()).hasSize(1);
        assertThat(redis.hasKey(searchKey(messy))).isTrue();
        assertThat(redis.getExpire(searchKey(messy))).isPositive();

        // A second Goa villa, inserted behind the service's back: no event, no eviction.
        properties.save(factory.create(TestRequests.validVilla(), host));

        assertThat(searchService.search(new SearchCriteria("goa", null, null, 0, 20)).content())
                .as("served from the cache, so the new row is not visible yet")
                .hasSize(1);
    }

    @Test
    @DisplayName("a new listing flushes its city's pages, so it shows up immediately")
    void createFlushesItsCity() {
        SearchCriteria goa = new SearchCriteria("goa", null, null, 0, 20);
        assertThat(searchService.search(goa).content()).isEmpty();   // an empty page, cached

        propertyService.create(TestRequests.validVilla(), host.getId());

        assertThat(searchService.search(goa).content()).hasSize(1);
    }

    @Test
    @DisplayName("a change flushes only the search pages that could contain the listing")
    void onlyAffectedPartitionsAreFlushed() {
        long villaId = propertyService.create(TestRequests.validVilla(), host.getId()).id();   // Goa
        propertyService.create(TestRequests.validApartment(), host.getId());                  // Chennai
        SearchCriteria goa = new SearchCriteria("goa", null, null, 0, 20);
        SearchCriteria chennai = new SearchCriteria("chennai", null, null, 0, 20);
        SearchCriteria anyCity = new SearchCriteria(null, null, null, 0, 20);
        searchService.search(goa);
        searchService.search(chennai);
        searchService.search(anyCity);

        PropertyRequest change = TestRequests.validVilla();
        change.setTitle("Renamed villa");
        propertyService.update(villaId, change, host.getId());

        assertThat(redis.hasKey(searchKey(goa))).as("Goa pages").isFalse();
        assertThat(redis.hasKey(searchKey(anyCity))).as("no-city pages").isFalse();
        assertThat(redis.hasKey(searchKey(chennai))).as("Chennai pages survive").isTrue();
    }

    @Test
    @DisplayName("a listing that moves city flushes the old city's pages and the new city's")
    void moveFlushesBothCities() {
        long villaId = propertyService.create(TestRequests.validVilla(), host.getId()).id();   // Goa
        SearchCriteria goa = new SearchCriteria("goa", null, null, 0, 20);
        SearchCriteria mumbai = new SearchCriteria("mumbai", null, null, 0, 20);
        assertThat(searchService.search(goa).content()).hasSize(1);
        assertThat(searchService.search(mumbai).content()).isEmpty();

        PropertyRequest move = TestRequests.validVilla();
        move.setCity("Mumbai");
        propertyService.update(villaId, move, host.getId());

        assertThat(searchService.search(goa).content()).isEmpty();
        assertThat(searchService.search(mumbai).content()).hasSize(1);
    }

    // ------------------------------------------------------------------ helpers

    private Cache localTier() {
        return ((TwoLevelCache) cacheManager.getCache(CacheNames.PROPERTY_BY_ID)).localTier();
    }

    private String listingKey(long id) {
        return settings.keyPrefix() + CacheNames.PROPERTY_BY_ID + "::" + id;
    }

    private String searchKey(SearchCriteria criteria) {
        return settings.keyPrefix() + CacheNames.PROPERTY_SEARCH + "::" + SearchCacheKeys.of(criteria);
    }

    private void deleteRowBehindTheServicesBack(long id) {
        jdbc.update("DELETE FROM properties WHERE id = ?", id);
    }
}
