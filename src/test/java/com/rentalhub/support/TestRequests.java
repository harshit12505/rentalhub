package com.rentalhub.support;

import com.rentalhub.domain.model.User;
import com.rentalhub.domain.model.enums.Currency;
import com.rentalhub.domain.model.enums.PropertyType;
import com.rentalhub.domain.model.enums.UserRole;
import com.rentalhub.dto.BookingRequest;
import com.rentalhub.dto.PropertyRequest;

import java.math.BigDecimal;
import java.time.LocalDate;
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

    public static User secondGuest() {
        return new User("Meera Iyer", "meera@example.com", UserRole.GUEST);
    }

    /** Stripe's test card that always succeeds; the simulator the tests run with honours it too. */
    public static final String PAYS = "pm_card_visa";

    public static BookingRequest booking(long propertyId, LocalDate checkIn, LocalDate checkOut, int guests) {
        return booking(propertyId, checkIn, checkOut, guests, PAYS);
    }

    public static BookingRequest booking(long propertyId, LocalDate checkIn, LocalDate checkOut, int guests,
                                         String paymentMethodId) {
        BookingRequest request = new BookingRequest();
        request.setPropertyId(propertyId);
        request.setCheckIn(checkIn);
        request.setCheckOut(checkOut);
        request.setGuests(guests);
        request.setPaymentMethodId(paymentMethodId);
        return request;
    }

    /** Shared fields that satisfy every type's rules on their own. */
    public static PropertyRequest base(PropertyType type) {
        PropertyRequest request = new PropertyRequest();
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

    public static PropertyRequest validApartment() {
        PropertyRequest request = base(PropertyType.APARTMENT);
        request.getAttributes().put("floorNumber", "3");
        return request;
    }

    public static PropertyRequest validVilla() {
        PropertyRequest request = base(PropertyType.VILLA);
        request.setCity("Goa");
        request.setPricePerNight(new BigDecimal("12000.00"));
        request.setMaxGuests(6);
        request.setBedrooms(3);
        request.getAttributes().put("plotAreaSqm", "450.00");
        request.getAttributes().put("hasPool", "true");
        return request;
    }

    public static PropertyRequest validCabin() {
        PropertyRequest request = base(PropertyType.CABIN);
        request.setCity("Manali");
        request.getAttributes().put("heatingType", "wood_stove");
        request.getAttributes().put("distanceToTownKm", "14.5");
        return request;
    }

    public static PropertyRequest validStudio() {
        PropertyRequest request = base(PropertyType.STUDIO);
        request.setBedrooms(0);
        request.getAttributes().put("areaSqm", "28");
        request.getAttributes().put("hasSofaBed", "true");
        return request;
    }

    /** One valid request per type, for rules that must hold for every type. */
    public static List<PropertyRequest> oneValidPerType() {
        return List.of(validApartment(), validVilla(), validCabin(), validStudio());
    }
}
