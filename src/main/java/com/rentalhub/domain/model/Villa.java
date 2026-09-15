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
@DiscriminatorValue("VILLA")
@Getter
@Setter
public class Villa extends Property {

    @Column(name = "plot_area_sqm", precision = 10, scale = 2)
    private BigDecimal plotAreaSqm;

    @Column(name = "has_pool")
    private Boolean hasPool;

    @Override
    public PropertyType getType() {
        return PropertyType.VILLA;
    }

    @Override
    public Map<String, Object> typeAttributes() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("plotAreaSqm", plotAreaSqm);
        attributes.put("hasPool", hasPool);
        return Collections.unmodifiableMap(attributes);
    }
}
