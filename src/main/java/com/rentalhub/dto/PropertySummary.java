package com.rentalhub.dto;

import com.rentalhub.domain.model.enums.Currency;
import com.rentalhub.domain.model.enums.PropertyType;

import java.math.BigDecimal;

/**
 * One listing card in search results: just what a card shows, which keeps cached
 * search pages small.
 *
 * @param coverImageUrl the first image by sort order, or null if the listing has none
 * @param displayPrice  the nightly price in the currency the viewer asked for, or null.
 *                      Always null in the cache (see PropertyView)
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
        String coverImageUrl,
        DisplayPrice displayPrice) {

    /** A copy with the price shown in another currency too. */
    public PropertySummary withDisplayPrice(DisplayPrice displayPrice) {
        return new PropertySummary(id, type, title, city, country, pricePerNight, currency, maxGuests, bedrooms,
                bathrooms, coverImageUrl, displayPrice);
    }
}
