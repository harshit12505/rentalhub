package com.rentalhub.factory.impl;

import com.rentalhub.domain.model.Apartment;
import com.rentalhub.domain.model.enums.PropertyType;
import com.rentalhub.dto.PropertyRequest;
import com.rentalhub.exception.PropertyValidationException;
import com.rentalhub.factory.AbstractPropertyCreator;
import com.rentalhub.factory.AttributeKind;
import com.rentalhub.factory.AttributeSpec;
import com.rentalhub.factory.TypeAttributes;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ApartmentCreator extends AbstractPropertyCreator<Apartment> {

    static final String FLOOR = "floorNumber";
    static final String ELEVATOR = "hasElevator";

    /** Above this floor a walk-up is a real accessibility problem, so the host must say whether there is a lift. */
    private static final int LIFT_DECLARATION_FLOOR = 4;

    private static final List<AttributeSpec> ATTRIBUTES = List.of(
            AttributeSpec.required(FLOOR, AttributeKind.INTEGER),
            AttributeSpec.optional(ELEVATOR, AttributeKind.BOOLEAN));

    public ApartmentCreator() {
        super(Apartment.class, Apartment::new);
    }

    @Override
    public PropertyType supportedType() {
        return PropertyType.APARTMENT;
    }

    @Override
    public List<AttributeSpec> attributeSpecs() {
        return ATTRIBUTES;
    }

    @Override
    protected void validateSpecific(PropertyRequest request, TypeAttributes attributes) {
        int floor = attributes.integer(FLOOR);
        if (floor < 0) {
            throw PropertyValidationException.onField(
                    AttributeSpec.fieldPathOf(FLOOR), "property.apartment.floor.negative");
        }
        if (floor > LIFT_DECLARATION_FLOOR && attributes.bool(ELEVATOR) == null) {
            throw PropertyValidationException.onField(
                    AttributeSpec.fieldPathOf(ELEVATOR), "property.apartment.elevator.required", LIFT_DECLARATION_FLOOR);
        }
        // A one-room home is a studio; listing it as an apartment misleads guests.
        if (bedroomsOf(request) < 1) {
            throw PropertyValidationException.onField("bedrooms", "property.apartment.bedrooms.min");
        }
    }

    @Override
    protected void applyTypeFields(Apartment apartment, TypeAttributes attributes) {
        apartment.setFloorNumber(attributes.integer(FLOOR));
        // Stored as given: null means "not stated", which is allowed up to floor 4.
        apartment.setHasElevator(attributes.bool(ELEVATOR));
    }
}
