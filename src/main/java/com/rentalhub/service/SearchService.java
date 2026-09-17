package com.rentalhub.service;

import com.rentalhub.cache.CacheNames;
import com.rentalhub.domain.model.Property;
import com.rentalhub.domain.repository.PropertyImageRepository;
import com.rentalhub.domain.repository.PropertyRepository;
import com.rentalhub.dto.PropertySummary;
import com.rentalhub.dto.SearchCriteria;
import com.rentalhub.dto.SearchResultPage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class SearchService {

    /** Newest first; id breaks ties so pages never overlap or skip a listing. */
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final PropertyRepository properties;
    private final PropertyImageRepository images;
    private final CurrencyService currencies;

    public SearchService(PropertyRepository properties, PropertyImageRepository images, CurrencyService currencies) {
        this.properties = properties;
        this.images = images;
        this.currencies = currencies;
    }

    /**
     * One page of active listings matching the criteria.
     *
     * Cached in Redis only, under a key built from the full normalised filter set (see
     * SearchCacheKeys), so every app instance shares the same pages. A listing change
     * flushes only the pages that could contain it (see PropertyCacheInvalidator). A page
     * built without exchange rates is not cached: it is incomplete, and it would outlive
     * the outage that caused it.
     *
     * A price limit is compared in each listing's own currency: the limit is converted into
     * every currency (see CurrencyService.ceilings), and the query compares each listing with
     * the ceiling for its currency. Prices themselves are never converted or stored converted.
     *
     * A fixed number of queries per page, however many listings it holds: the listings,
     * their total count (for page numbers), and all their cover images in one go.
     * Fetching each listing's images separately would be one extra query per row (the
     * "N+1 problem").
     */
    @Cacheable(cacheNames = CacheNames.PROPERTY_SEARCH, keyGenerator = CacheNames.SEARCH_KEY_GENERATOR,
            unless = "#result.exchangeRatesUnavailable()")
    public SearchResultPage search(SearchCriteria criteria) {
        log.atDebug().setMessage("cache.miss")
                .addKeyValue("cache", CacheNames.PROPERTY_SEARCH)
                .addKeyValue("criteria", criteria)
                .addKeyValue("action", "query-database")
                .log();
        // Worked out before the query, outside its transaction: it may fetch rates over HTTP.
        PriceCeilings ceilings = criteria.maxPrice() == null
                ? null
                : currencies.ceilings(criteria.maxPrice(), criteria.currency());
        boolean ratesMissing = ceilings != null && !ceilings.complete();
        if (ratesMissing) {
            log.atWarn().setMessage("search.priceFilter.partial")
                    .addKeyValue("maxPrice", criteria.maxPrice())
                    .addKeyValue("currency", criteria.currency())
                    .addKeyValue("comparedCurrencies", ceilings.byCurrency().keySet())
                    .log();
        }

        Page<Property> page = properties.search(
                criteria.city(), criteria.minGuests(), ceilings == null ? null : ceilings.byCurrency(),
                PageRequest.of(criteria.page(), criteria.size(), NEWEST_FIRST));

        Map<Long, String> covers = coverImages(page.getContent());
        List<PropertySummary> content = page.getContent().stream()
                .map(property -> PropertyViews.toSummary(property, covers.get(property.getId())))
                .toList();
        return new SearchResultPage(content, criteria.page(), criteria.size(),
                page.getTotalElements(), page.getTotalPages(), ratesMissing);
    }

    private Map<Long, String> coverImages(List<Property> listings) {
        if (listings.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = listings.stream().map(Property::getId).toList();
        Map<Long, String> covers = new HashMap<>();
        // Rows arrive in sort order, so the first URL seen for a listing is its cover.
        for (PropertyImageRepository.ImageUrl image : images.findImageUrls(ids)) {
            covers.putIfAbsent(image.getPropertyId(), image.getUrl());
        }
        return covers;
    }
}
