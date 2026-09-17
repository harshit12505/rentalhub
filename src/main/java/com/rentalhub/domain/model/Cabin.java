package com.rentalhub.domain.model;

import com.rentalhub.domain.model.enums.PropertyType;
import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.envers.Audited;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@Entity
@Audited
@DiscriminatorValue("CABIN")
@Getter
@Setter
public class Cabin extends Property {

    /** One of the codes CabinCreator allows, e.g. WOOD_STOVE. */
    @Column(name = "heating_type", length = 30)
    private String heatingType;

    @Column(name = "distance_to_town_km", precision = 6, scale = 2)
    private BigDecimal distanceToTownKm;

    @Override
    public PropertyType getType() {
        return PropertyType.CABIN;
    }

    @Override
    public Map<String, Object> typeAttributes() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("heatingType", heatingType);
        attributes.put("distanceToTownKm", distanceToTownKm);
        return Collections.unmodifiableMap(attributes);
    }
}
