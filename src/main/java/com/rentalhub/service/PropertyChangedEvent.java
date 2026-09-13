package com.rentalhub.service;

import com.rentalhub.domain.model.Property;

/**
 * "This listing changed." Published inside the transaction that changes it, and acted
 * on only after that transaction commits (see PropertyCacheInvalidator).
 *
 * It carries both the old and the new city because a listing that moves from Goa to
 * Mumbai leaves stale search pages in both.
 *
 * @param previousCity null for a new listing
 * @param currentCity  null for a deleted listing
 */
public record PropertyChangedEvent(long propertyId, String previousCity, String currentCity) {

    public static PropertyChangedEvent created(Property property) {
        return new PropertyChangedEvent(property.getId(), null, property.getCity());
    }

    public static PropertyChangedEvent updated(Property property, String previousCity) {
        return new PropertyChangedEvent(property.getId(), previousCity, property.getCity());
    }

    public static PropertyChangedEvent deleted(Property property) {
        return new PropertyChangedEvent(property.getId(), property.getCity(), null);
    }
}
