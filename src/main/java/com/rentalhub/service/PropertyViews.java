package com.rentalhub.service;

import com.rentalhub.domain.model.Property;
import com.rentalhub.dto.PropertySummary;
import com.rentalhub.dto.PropertyView;

/**
 * Turns entities into the records that are cached and returned.
 *
 * Callers must have loaded what is read here (host and images for a view), because
 * with open-in-view off there is no database session left to lazy-load them.
 */
public final class PropertyViews {

    private PropertyViews() {
    }

    static PropertyView toView(Property property) {
        return new PropertyView(
                property.getId(),
                property.getType(),
                property.getTitle(),
                property.getDescription(),
                property.getCity(),
                property.getCountry(),
                property.getAddress(),
                property.getPricePerNight(),
                property.getCurrency(),
                property.getMaxGuests(),
                property.getBedrooms(),
                property.getBathrooms(),
                property.isActive(),
                property.getAvailableUntil(),
                new PropertyView.Host(property.getHost().getId(), property.getHost().getFullName()),
                property.getImages().stream()
                        .map(image -> new PropertyView.Image(image.getId(), image.getUrl(), image.getSortOrder()))
                        .toList(),
                property.typeAttributes(),
                property.getVersion(),
                property.getUpdatedAt(),
                null);
    }

    public static PropertySummary toSummary(Property property, String coverImageUrl) {
        return new PropertySummary(
                property.getId(),
                property.getType(),
                property.getTitle(),
                property.getCity(),
                property.getCountry(),
                property.getPricePerNight(),
                property.getCurrency(),
                property.getMaxGuests(),
                property.getBedrooms(),
                property.getBathrooms(),
                coverImageUrl,
                null);
    }
}
