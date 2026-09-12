package com.rentalhub.factory.impl;

import com.rentalhub.domain.model.Studio;
import com.rentalhub.domain.model.enums.PropertyType;
import com.rentalhub.dto.CreatePropertyRequest;
import com.rentalhub.exception.PropertyValidationException;
import com.rentalhub.factory.AbstractPropertyCreator;
import com.rentalhub.factory.AttributeKind;
import com.rentalhub.factory.AttributeSpec;
import com.rentalhub.factory.TypeAttributes;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

@Component
public class StudioCreator extends AbstractPropertyCreator<Studio> {

    static final String AREA = "areaSqm";
    static final String SOFA_BED = "hasSofaBed";

    private static final int MAX_GUESTS = 3;
    private static final BigDecimal MIN_AREA_SQM = new BigDecimal("12");

    private static final List<AttributeSpec> ATTRIBUTES = List.of(
            AttributeSpec.required(AREA, AttributeKind.DECIMAL),
            AttributeSpec.optional(SOFA_BED, AttributeKind.BOOLEAN));

    public StudioCreator() {
        super(Studio.class);
    }

    @Override
    public PropertyType supportedType() {
        return PropertyType.STUDIO;
    }

    @Override
    public List<AttributeSpec> attributeSpecs() {
        return ATTRIBUTES;
    }

    @Override
    protected void validateSpecific(CreatePropertyRequest request, TypeAttributes attributes) {
        // A studio is one room by definition, so capacity is capped. Anything
        // larger belongs in another category.
        if (request.getMaxGuests() > MAX_GUESTS) {
            throw PropertyValidationException.onField("maxGuests", "property.studio.guests.max", MAX_GUESTS);
        }
        if (attributes.decimal(AREA).compareTo(MIN_AREA_SQM) < 0) {
            throw PropertyValidationException.onField(
                    AttributeSpec.fieldPathOf(AREA), "property.studio.area.min", MIN_AREA_SQM);
        }
    }

    @Override
    protected Studio build(TypeAttributes attributes) {
        Studio studio = new Studio();
        studio.setAreaSqm(attributes.decimal(AREA));
        studio.setHasSofaBed(attributes.bool(SOFA_BED));
        return studio;
    }

    /** One room means zero bedrooms, whatever the request said. Enforced, not trusted. */
    @Override
    protected void enforceInvariants(Studio studio) {
        studio.setBedrooms(0);
    }
}
