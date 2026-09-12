package com.rentalhub.factory;

import com.rentalhub.domain.model.Property;
import com.rentalhub.domain.model.User;
import com.rentalhub.dto.CreatePropertyRequest;
import com.rentalhub.exception.PropertyValidationException;

import java.math.BigDecimal;

/**
 * Holds the rules every listing shares, so the concrete creators only contain what
 * actually differs between property types.
 *
 * This is the Template Method pattern inside the Factory: {@link #create} fixes the
 * order of the steps and is final, so no subtype can skip or reorder them; subtypes
 * fill in {@link #validateSpecific} and {@link #build}.
 *
 * @param <T> the entity subtype this creator builds
 */
public abstract class AbstractPropertyCreator<T extends Property> implements PropertyCreator {

    private final Class<T> entityClass;

    protected AbstractPropertyCreator(Class<T> entityClass) {
        this.entityClass = entityClass;
    }

    @Override
    public final Class<T> entityClass() {
        return entityClass;
    }

    @Override
    public final Property create(CreatePropertyRequest request, User host) {
        validateCommon(request);
        TypeAttributes attributes = TypeAttributes.parse(request.getAttributes(), attributeSpecs());
        validateSpecific(request, attributes);

        T property = build(attributes);
        applyCommonFields(property, request, host);
        enforceInvariants(property);
        return property;
    }

    /**
     * Rules that hold for every listing regardless of type.
     *
     * Bean validation on the request checks these too, but only where a controller
     * asks for it. The factory checks again because it is the one path every listing
     * takes, whether it arrives from a form, REST, GraphQL or the demo seeder.
     */
    protected void validateCommon(CreatePropertyRequest request) {
        if (request.getCurrency() == null) {
            throw PropertyValidationException.onField("currency", "property.currency.required");
        }
        BigDecimal price = request.getPricePerNight();
        if (price == null || price.signum() <= 0) {
            throw PropertyValidationException.onField("pricePerNight", "property.price.positive");
        }
        // A price must be expressible in the currency: ₹2500.125 is not a real price.
        // stripTrailingZeros() so that "2500.00" counts as two decimals at most, not two exactly.
        int allowedDecimals = request.getCurrency().fractionDigits();
        if (price.stripTrailingZeros().scale() > allowedDecimals) {
            throw PropertyValidationException.onField("pricePerNight", "property.price.precision", allowedDecimals);
        }
        if (request.getMaxGuests() == null || request.getMaxGuests() < 1) {
            throw PropertyValidationException.onField("maxGuests", "property.guests.min");
        }
        if (bedroomsOf(request) < 0) {
            throw PropertyValidationException.onField("bedrooms", "property.bedrooms.negative");
        }
        if (request.getBathrooms() != null && request.getBathrooms() < 0) {
            throw PropertyValidationException.onField("bathrooms", "property.bathrooms.negative");
        }
    }

    /** Rules unique to this property type. Attributes are already parsed and present if required. */
    protected abstract void validateSpecific(CreatePropertyRequest request, TypeAttributes attributes);

    /** Construct the concrete subtype and set its own fields. */
    protected abstract T build(TypeAttributes attributes);

    /**
     * Runs last, after the shared fields are copied, for rules that override one of
     * them (a studio always has zero bedrooms). Running last means nothing can undo it.
     */
    protected void enforceInvariants(T property) {
    }

    /** Bedrooms is optional on the request and defaults to zero. */
    protected static int bedroomsOf(CreatePropertyRequest request) {
        return request.getBedrooms() == null ? 0 : request.getBedrooms();
    }

    private void applyCommonFields(Property property, CreatePropertyRequest request, User host) {
        property.setHost(host);
        property.setTitle(request.getTitle());
        property.setDescription(request.getDescription());
        property.setCity(request.getCity());
        property.setCountry(request.getCountry());
        property.setAddress(request.getAddress());
        property.setPricePerNight(request.getPricePerNight());
        property.setCurrency(request.getCurrency());
        property.setMaxGuests(request.getMaxGuests());
        property.setBedrooms(bedroomsOf(request));
        property.setBathrooms(request.getBathrooms() == null ? 0 : request.getBathrooms());
        property.setAvailableUntil(request.getAvailableUntil());
        property.setActive(true);
    }
}
