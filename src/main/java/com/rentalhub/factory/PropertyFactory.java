package com.rentalhub.factory;

import com.rentalhub.domain.model.Property;
import com.rentalhub.domain.model.User;
import com.rentalhub.domain.model.enums.PropertyType;
import com.rentalhub.dto.PropertyRequest;
import com.rentalhub.exception.PropertyValidationException;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The single entry point for turning a request into a Property entity, or applying
 * one to an existing listing.
 *
 * Spring injects every PropertyCreator bean into the constructor and the factory
 * indexes them by the type each one declares. Callers never see the subtypes, never
 * write a switch statement, and never change when a new type appears.
 */
@Component
public class PropertyFactory {

    private final Map<PropertyType, PropertyCreator> creators = new EnumMap<>(PropertyType.class);

    /**
     * Fails at startup if any PropertyType has no creator, or two. A forgotten
     * creator should stop the application from booting, not surface as an error
     * the first time a host picks that type.
     */
    public PropertyFactory(List<PropertyCreator> availableCreators) {
        for (PropertyCreator creator : availableCreators) {
            PropertyCreator existing = creators.put(creator.supportedType(), creator);
            if (existing != null) {
                throw new IllegalStateException("Two PropertyCreators registered for " + creator.supportedType()
                        + ": " + existing.getClass().getSimpleName() + " and " + creator.getClass().getSimpleName());
            }
        }
        Set<PropertyType> missing = EnumSet.allOf(PropertyType.class);
        missing.removeAll(creators.keySet());
        if (!missing.isEmpty()) {
            throw new IllegalStateException("No PropertyCreator registered for " + missing);
        }
    }

    public Property create(PropertyRequest request, User host) {
        if (request.getType() == null) {
            throw PropertyValidationException.onField("type", "property.type.required");
        }
        return creators.get(request.getType()).create(request, host);
    }

    /**
     * Applies a full replacement to an existing listing, under the same rules as
     * creation. The creator is chosen by the listing's own type. A request naming a
     * different type is refused: a villa cannot become a studio, because that would be
     * a different entity class, with different columns and different rules.
     */
    public void update(Property existing, PropertyRequest request) {
        if (request.getType() != null && request.getType() != existing.getType()) {
            throw PropertyValidationException.onField("type", "property.type.cannotChange");
        }
        creators.get(existing.getType()).update(existing, request);
    }

    /** What the "new listing" form should ask for when this type is selected. */
    public List<AttributeSpec> attributeSpecs(PropertyType type) {
        return creators.get(type).attributeSpecs();
    }

    public Set<PropertyType> supportedTypes() {
        return Collections.unmodifiableSet(creators.keySet());
    }
}
