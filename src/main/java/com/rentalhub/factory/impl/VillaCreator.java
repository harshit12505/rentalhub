package com.rentalhub.factory.impl;

import com.rentalhub.domain.model.Villa;
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
public class VillaCreator extends AbstractPropertyCreator<Villa> {

    static final String PLOT_AREA = "plotAreaSqm";
    static final String POOL = "hasPool";

    private static final BigDecimal MIN_PLOT_AREA_SQM = new BigDecimal("100");
    private static final int MIN_GUESTS = 4;
    private static final int MIN_BEDROOMS = 2;

    private static final List<AttributeSpec> ATTRIBUTES = List.of(
            AttributeSpec.required(PLOT_AREA, AttributeKind.DECIMAL),
            AttributeSpec.optional(POOL, AttributeKind.BOOLEAN));

    public VillaCreator() {
        super(Villa.class);
    }

    @Override
    public PropertyType supportedType() {
        return PropertyType.VILLA;
    }

    @Override
    public List<AttributeSpec> attributeSpecs() {
        return ATTRIBUTES;
    }

    @Override
    protected void validateSpecific(CreatePropertyRequest request, TypeAttributes attributes) {
        if (attributes.decimal(PLOT_AREA).compareTo(MIN_PLOT_AREA_SQM) < 0) {
            throw PropertyValidationException.onField(
                    AttributeSpec.fieldPathOf(PLOT_AREA), "property.villa.plotArea.min", MIN_PLOT_AREA_SQM);
        }
        // A villa listed for two people is almost always a mis-categorised flat.
        if (request.getMaxGuests() < MIN_GUESTS) {
            throw PropertyValidationException.onField("maxGuests", "property.villa.guests.min", MIN_GUESTS);
        }
        if (bedroomsOf(request) < MIN_BEDROOMS) {
            throw PropertyValidationException.onField("bedrooms", "property.villa.bedrooms.min", MIN_BEDROOMS);
        }
    }

    @Override
    protected Villa build(TypeAttributes attributes) {
        Villa villa = new Villa();
        villa.setPlotAreaSqm(attributes.decimal(PLOT_AREA));
        villa.setHasPool(attributes.bool(POOL));
        return villa;
    }
}
