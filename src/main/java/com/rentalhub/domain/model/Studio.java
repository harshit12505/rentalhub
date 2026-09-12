package com.rentalhub.domain.model;

import com.rentalhub.domain.model.enums.PropertyType;
import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@Entity
@DiscriminatorValue("STUDIO")
@Getter
@Setter
public class Studio extends Property {

    @Column(name = "area_sqm", precision = 10, scale = 2)
    private BigDecimal areaSqm;

    @Column(name = "has_sofa_bed")
    private Boolean hasSofaBed;

    @Override
    public PropertyType getType() {
        return PropertyType.STUDIO;
    }

    @Override
    public Map<String, Object> typeAttributes() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("areaSqm", areaSqm);
        attributes.put("hasSofaBed", hasSofaBed);
        return Collections.unmodifiableMap(attributes);
    }
}
