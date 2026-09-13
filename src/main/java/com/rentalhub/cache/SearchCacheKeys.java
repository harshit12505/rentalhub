package com.rentalhub.cache;

import com.rentalhub.dto.SearchCriteria;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * The key layout for cached search pages, kept in one place so that writing a key
 * and invalidating it can never disagree.
 *
 * A key starts with the city: {@code city:goa|guests:4|maxPrice:5000|page:0|size:20}.
 * Putting the city first partitions the cache. Every page for Goa shares the prefix
 * {@code city:goa|}, and every search with no city filter shares {@code city:|}. A Goa
 * listing can only ever appear in those two partitions, so when it changes only they
 * are flushed, and Mumbai's cached pages survive.
 */
public final class SearchCacheKeys {

    private SearchCacheKeys() {
    }

    public static String of(SearchCriteria criteria) {
        return cityPrefix(criteria.city())
                + "guests:" + (criteria.minGuests() == null ? "" : criteria.minGuests())
                + "|maxPrice:" + (criteria.maxPrice() == null ? "" : criteria.maxPrice().toPlainString())
                + "|page:" + criteria.page()
                + "|size:" + criteria.size();
    }

    /**
     * A Redis glob pattern matching every cached page for this city. A null or blank
     * city means the partition of searches that had no city filter.
     */
    public static String partitionPattern(String city) {
        return cityPrefix(SearchCriteria.normaliseCity(city)) + "*";
    }

    private static String cityPrefix(String normalisedCity) {
        return "city:" + encode(normalisedCity) + "|";
    }

    /**
     * URL-encoding keeps '|' and Redis glob characters ('?', '[', ']') out of the key,
     * since a city name could contain them. URLEncoder leaves '*' alone, so that one
     * is encoded by hand.
     */
    private static String encode(String normalisedCity) {
        return normalisedCity == null
                ? ""
                : URLEncoder.encode(normalisedCity, StandardCharsets.UTF_8).replace("*", "%2A");
    }
}
