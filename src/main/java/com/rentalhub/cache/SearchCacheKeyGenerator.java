package com.rentalhub.cache;

import com.rentalhub.dto.SearchCriteria;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * Tells Spring's @Cacheable how to name a cached search page. Registered by name and
 * referenced from SearchService, so the key format lives in SearchCacheKeys alone.
 */
@Component(CacheNames.SEARCH_KEY_GENERATOR)
class SearchCacheKeyGenerator implements KeyGenerator {

    @Override
    public Object generate(Object target, Method method, Object... params) {
        if (params.length != 1 || !(params[0] instanceof SearchCriteria criteria)) {
            throw new IllegalStateException(
                    method + " is cached as a search, so it must take exactly one SearchCriteria");
        }
        return SearchCacheKeys.of(criteria);
    }
}
