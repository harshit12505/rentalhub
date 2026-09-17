package com.rentalhub.dto;

import com.rentalhub.domain.model.enums.Currency;
import com.rentalhub.domain.model.enums.PropertyType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything the listing detail page and API show about one listing.
 *
 * This, not the entity, is what gets cached. An entity is tied to the database
 * session that loaded it; once that closes, touching one of its lazy associations
 * throws. A record is plain data that can be kept in memory, sent to Redis as JSON
 * and read back anywhere.
 *
 * It is also immutable, and that matters: the in-process cache hands the very same
 * instance to every request that asks for this listing, so nobody may change it.
 *
 * @param attributes   the type-specific fields (see Property.typeAttributes()), null meaning "not stated"
 * @param version      the optimistic-locking version the view was built from
 * @param displayPrice the nightly price in the currency the viewer asked for, or null. Always
 *                     null in the cache: it is added per request, to a copy, by
 *                     CurrencyService, because a converted price is only true for a moment
 */
public record PropertyView(
        long id,
        PropertyType type,
        String title,
        String description,
        String city,
        String country,
        String address,
        BigDecimal pricePerNight,
        Currency currency,
        int maxGuests,
        int bedrooms,
        int bathrooms,
        boolean active,
        LocalDate availableUntil,
        Host host,
        List<Image> images,
        Map<String, Object> attributes,
        long version,
        Instant updatedAt,
        DisplayPrice displayPrice) {

    public PropertyView {
        images = List.copyOf(images);
        // Map.copyOf would reject the null values that mean "not stated".
        attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    /** A copy with the price shown in another currency too. The cached original is never changed. */
    public PropertyView withDisplayPrice(DisplayPrice displayPrice) {
        return new PropertyView(id, type, title, description, city, country, address, pricePerNight, currency,
                maxGuests, bedrooms, bathrooms, active, availableUntil, host, images, attributes, version, updatedAt,
                displayPrice);
    }

    public record Host(long id, String fullName) {
    }

    public record Image(String url, int sortOrder) {
    }
}
