package com.rentalhub.cache;

/** Names shared by the cache configuration and the @Cacheable annotations that use it. */
public final class CacheNames {

    /** One listing's detail view, by id. Two tiers: Caffeine in front of Redis. */
    public static final String PROPERTY_BY_ID = "propertyById";

    /** Pages of search results, by normalised filter set. Redis only. */
    public static final String PROPERTY_SEARCH = "propertySearch";

    /** Bean name of the key generator for PROPERTY_SEARCH. */
    public static final String SEARCH_KEY_GENERATOR = "searchCacheKeyGenerator";

    private CacheNames() {
    }
}
