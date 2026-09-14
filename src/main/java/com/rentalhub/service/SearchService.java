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

    public SearchService(PropertyRepository properties, PropertyImageRepository images) {
        this.properties = properties;
        this.images = images;
    }

    /**
     * One page of active listings matching the criteria.
     *
     * Cached in Redis only, under a key built from the full normalised filter set (see
     * SearchCacheKeys), so every app instance shares the same pages. A listing change
     * flushes only the pages that could contain it (see PropertyCacheInvalidator).
     *
     * A fixed number of queries per page, however many listings it holds: the listings,
     * their total count (for page numbers), and all their cover images in one go.
     * Fetching each listing's images separately would be one extra query per row (the
     * "N+1 problem").
     */
    @Cacheable(cacheNames = CacheNames.PROPERTY_SEARCH, keyGenerator = CacheNames.SEARCH_KEY_GENERATOR)
    public SearchResultPage search(SearchCriteria criteria) {
        log.debug("cache.miss cache={} criteria={} action=query-database", CacheNames.PROPERTY_SEARCH, criteria);
        Page<Property> page = properties.search(
                criteria.city(), criteria.minGuests(), criteria.maxPrice(),
                PageRequest.of(criteria.page(), criteria.size(), NEWEST_FIRST));

        Map<Long, String> covers = coverImages(page.getContent());
        List<PropertySummary> content = page.getContent().stream()
                .map(property -> PropertyViews.toSummary(property, covers.get(property.getId())))
                .toList();
        return new SearchResultPage(content, criteria.page(), criteria.size(),
                page.getTotalElements(), page.getTotalPages());
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
