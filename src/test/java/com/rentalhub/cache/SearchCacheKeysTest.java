package com.rentalhub.cache;

import com.rentalhub.dto.SearchCriteria;
import com.rentalhub.service.PropertyChangedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class SearchCacheKeysTest {

    @Test
    @DisplayName("equivalent searches share one key")
    void equivalentSearchesShareAKey() {
        SearchCriteria messy = new SearchCriteria("  Goa ", 4, new BigDecimal("5000.00"), 0, 20);
        SearchCriteria clean = new SearchCriteria("goa", 4, new BigDecimal("5000"), 0, 20);

        assertThat(SearchCacheKeys.of(messy))
                .isEqualTo(SearchCacheKeys.of(clean))
                .isEqualTo("city:goa|guests:4|maxPrice:5000|page:0|size:20");
    }

    @Test
    @DisplayName("a search with no filters lands in the no-city partition")
    void noFilters() {
        assertThat(SearchCacheKeys.of(new SearchCriteria(" ", 0, null, 0, 20)))
                .isEqualTo("city:|guests:|maxPrice:|page:0|size:20");
    }

    @Test
    @DisplayName("page cannot be negative and size is kept between 1 and 50")
    void pagingIsBounded() {
        SearchCriteria criteria = new SearchCriteria(null, null, null, -3, 500);

        assertThat(criteria.page()).isZero();
        assertThat(criteria.size()).isEqualTo(SearchCriteria.MAX_PAGE_SIZE);
    }

    @Test
    @DisplayName("a city's partition pattern matches its own pages and no other city's")
    void partitionsAreExact() {
        String goaPattern = SearchCacheKeys.partitionPattern("Goa");
        String goaKey = SearchCacheKeys.of(new SearchCriteria("goa", null, null, 3, 20));
        String goaBeachKey = SearchCacheKeys.of(new SearchCriteria("goa beach", null, null, 0, 20));

        assertThat(goaPattern).isEqualTo("city:goa|*");
        assertThat(goaKey).startsWith("city:goa|");
        // The '|' after the city is what stops "goa" from also matching "goa beach".
        assertThat(goaBeachKey).doesNotStartWith("city:goa|");
    }

    @Test
    @DisplayName("city names cannot smuggle separators or wildcards into a key")
    void cityIsEncoded() {
        SearchCriteria tricky = new SearchCriteria("a|b*c?", null, null, 0, 20);

        assertThat(SearchCacheKeys.of(tricky)).startsWith("city:a%7Cb%2Ac%3F|");
        assertThat(SearchCacheKeys.partitionPattern("a|b*c?")).isEqualTo("city:a%7Cb%2Ac%3F|*");
    }

    @Test
    @DisplayName("a listing moving city flushes old city, new city and the no-city pages")
    void partitionsForAMove() {
        PropertyChangedEvent moved = new PropertyChangedEvent(7L, "Goa", "Mumbai");

        assertThat(PropertyCacheInvalidator.partitionsToFlush(moved))
                .containsExactlyInAnyOrder("city:|*", "city:goa|*", "city:mumbai|*");
    }
}
