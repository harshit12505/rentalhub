package com.rentalhub.domain.model;

import com.rentalhub.domain.model.enums.PropertyType;
import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.envers.Audited;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@Entity
@Audited
@DiscriminatorValue("APARTMENT")
@Getter
@Setter
public class Apartment extends Property {

    @Column(name = "floor_number")
    private Integer floorNumber;

    @Column(name = "has_elevator")
    private Boolean hasElevator;

    @Override
    public PropertyType getType() {
        return PropertyType.APARTMENT;
    }

    @Override
    public Map<String, Object> typeAttributes() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("floorNumber", floorNumber);
        attributes.put("hasElevator", hasElevator);
        return Collections.unmodifiableMap(attributes);
    }
}
