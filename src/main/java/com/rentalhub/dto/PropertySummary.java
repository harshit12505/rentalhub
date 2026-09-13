package com.rentalhub.dto;

import com.rentalhub.domain.model.enums.Currency;
import com.rentalhub.domain.model.enums.PropertyType;

import java.math.BigDecimal;

/**
 * One listing card in search results: just what a card shows, which keeps cached
 * search pages small.
 *
 * @param coverImageUrl the first image by sort order, or null if the listing has none
 */
public record PropertySummary(
        long id,
        PropertyType type,
        String title,
        String city,
        String country,
        BigDecimal pricePerNight,
        Currency currency,
        int maxGuests,
        int bedrooms,
        int bathrooms,
        String coverImageUrl) {
}
