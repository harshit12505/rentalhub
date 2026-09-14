package com.rentalhub.factory;

import com.rentalhub.domain.model.Property;
import com.rentalhub.domain.model.User;
import com.rentalhub.domain.model.enums.PropertyType;
import com.rentalhub.dto.PropertyRequest;

import java.util.List;

/**
 * One implementation per property type. Each knows how to validate, build and update
 * its own subtype, and describes the extra fields that subtype has.
 *
 * Spring finds every implementation automatically, so a new property type is a new
 * class here. No controller, service, view or switch statement changes.
 */
public interface PropertyCreator {

    /** Which type this creator handles. Used as its key in the factory's map. */
    PropertyType supportedType();

    /** The entity class this creator builds. */
    Class<? extends Property> entityClass();

    /** The fields only this type has, in display order. */
    List<AttributeSpec> attributeSpecs();

    /** Validate the shared and type-specific rules, then build the entity. */
    Property create(PropertyRequest request, User host);

    /** Validate the same rules, then replace the existing entity's fields with the request's. */
    void update(Property existing, PropertyRequest request);
}
