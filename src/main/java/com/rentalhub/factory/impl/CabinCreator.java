package com.rentalhub.factory.impl;

import com.rentalhub.domain.model.Cabin;
import com.rentalhub.domain.model.enums.PropertyType;
import com.rentalhub.dto.PropertyRequest;
import com.rentalhub.exception.PropertyValidationException;
import com.rentalhub.factory.AbstractPropertyCreator;
import com.rentalhub.factory.AttributeKind;
import com.rentalhub.factory.AttributeSpec;
import com.rentalhub.factory.TypeAttributes;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

@Component
public class CabinCreator extends AbstractPropertyCreator<Cabin> {

    static final String HEATING = "heatingType";
    static final String DISTANCE = "distanceToTownKm";

    private static final BigDecimal MAX_DISTANCE_KM = new BigDecimal("200");

    /**
     * Cabins are usually remote, so heating is safety information: it is required,
     * and must be one of a fixed set of codes so it can be displayed and filtered
     * reliably. "NONE" is allowed, because stating "no heating" is still stating it.
     */
    private static final List<AttributeSpec> ATTRIBUTES = List.of(
            AttributeSpec.requiredChoice(HEATING, "WOOD_STOVE", "ELECTRIC", "GAS", "CENTRAL", "NONE"),
            AttributeSpec.required(DISTANCE, AttributeKind.DECIMAL));

    public CabinCreator() {
        super(Cabin.class, Cabin::new);
    }

    @Override
    public PropertyType supportedType() {
        return PropertyType.CABIN;
    }

    @Override
    public List<AttributeSpec> attributeSpecs() {
        return ATTRIBUTES;
    }

    @Override
    protected void validateSpecific(PropertyRequest request, TypeAttributes attributes) {
        BigDecimal distance = attributes.decimal(DISTANCE);
        if (distance.signum() < 0 || distance.compareTo(MAX_DISTANCE_KM) > 0) {
            throw PropertyValidationException.onField(
                    AttributeSpec.fieldPathOf(DISTANCE), "property.cabin.distance.range", MAX_DISTANCE_KM);
        }
    }

    @Override
    protected void applyTypeFields(Cabin cabin, TypeAttributes attributes) {
        cabin.setHeatingType(attributes.choice(HEATING));
        cabin.setDistanceToTownKm(attributes.decimal(DISTANCE));
    }
}
