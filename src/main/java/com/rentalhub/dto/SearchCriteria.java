package com.rentalhub.dto;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * One search, with every filter normalised so that equivalent searches look identical:
 * " Goa " and "goa" are the same city, 5000 and 5000.00 the same price.
 *
 * That matters because the criteria become the Redis cache key. Without normalising,
 * equivalent searches would each get their own cache entry, and each would miss.
 *
 * @param city      trimmed and lower-cased; null means any city
 * @param minGuests at least this many guests; null means any
 * @param maxPrice  at most this price per night, trailing zeros removed; null means any
 * @param page      zero-based page number
 * @param size      results per page, between 1 and {@link #MAX_PAGE_SIZE}
 */
public record SearchCriteria(String city, Integer minGuests, BigDecimal maxPrice, int page, int size) {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 50;

    public SearchCriteria {
        city = normaliseCity(city);
        minGuests = (minGuests == null || minGuests < 1) ? null : minGuests;
        maxPrice = maxPrice == null ? null : maxPrice.stripTrailingZeros();
        page = Math.max(page, 0);
        // Capped so no client can ask for a 10,000-row page, which also keeps the
        // number of distinct cache keys bounded.
        size = Math.clamp(size, 1, MAX_PAGE_SIZE);
    }

    /** The one definition of "the same city", shared with the cache-key code. */
    public static String normaliseCity(String city) {
        return (city == null || city.isBlank()) ? null : city.strip().toLowerCase(Locale.ROOT);
    }
}
