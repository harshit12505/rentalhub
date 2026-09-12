package com.rentalhub.support;

import com.rentalhub.domain.model.User;
import com.rentalhub.domain.model.enums.Currency;
import com.rentalhub.domain.model.enums.PropertyType;
import com.rentalhub.domain.model.enums.UserRole;
import com.rentalhub.dto.CreatePropertyRequest;

import java.math.BigDecimal;
import java.util.List;

/** Valid-by-default test data. Each test breaks only the one thing it is testing. */
public final class TestRequests {

    private TestRequests() {
    }

    public static User host() {
        return new User("Asha Menon", "asha@example.com", UserRole.HOST);
    }

    public static User guest() {
        return new User("Ravi Kumar", "ravi@example.com", UserRole.GUEST);
    }

    /** Shared fields that satisfy every type's rules on their own. */
    public static CreatePropertyRequest base(PropertyType type) {
        CreatePropertyRequest request = new CreatePropertyRequest();
        request.setType(type);
        request.setTitle("Test " + type.name().toLowerCase());
        request.setDescription("A pleasant place to stay.");
        request.setCity("Chennai");
        request.setCountry("India");
        request.setPricePerNight(new BigDecimal("2500.00"));
        request.setCurrency(Currency.INR);
        request.setMaxGuests(2);
        request.setBedrooms(1);
        request.setBathrooms(1);
        return request;
    }

    public static CreatePropertyRequest validApartment() {
        CreatePropertyRequest request = base(PropertyType.APARTMENT);
        request.getAttributes().put("floorNumber", "3");
        return request;
    }

    public static CreatePropertyRequest validVilla() {
        CreatePropertyRequest request = base(PropertyType.VILLA);
        request.setCity("Goa");
        request.setPricePerNight(new BigDecimal("12000.00"));
        request.setMaxGuests(6);
        request.setBedrooms(3);
        request.getAttributes().put("plotAreaSqm", "450.00");
        request.getAttributes().put("hasPool", "true");
        return request;
    }

    public static CreatePropertyRequest validCabin() {
        CreatePropertyRequest request = base(PropertyType.CABIN);
        request.setCity("Manali");
        request.getAttributes().put("heatingType", "wood_stove");
        request.getAttributes().put("distanceToTownKm", "14.5");
        return request;
    }

    public static CreatePropertyRequest validStudio() {
        CreatePropertyRequest request = base(PropertyType.STUDIO);
        request.setBedrooms(0);
        request.getAttributes().put("areaSqm", "28");
        request.getAttributes().put("hasSofaBed", "true");
        return request;
    }

    /** One valid request per type, for rules that must hold for every type. */
    public static List<CreatePropertyRequest> oneValidPerType() {
        return List.of(validApartment(), validVilla(), validCabin(), validStudio());
    }
}
