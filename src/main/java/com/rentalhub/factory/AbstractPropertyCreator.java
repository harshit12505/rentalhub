package com.rentalhub.factory;

import com.rentalhub.domain.model.Property;
import com.rentalhub.domain.model.User;
import com.rentalhub.dto.PropertyRequest;
import com.rentalhub.exception.PropertyValidationException;

import java.math.BigDecimal;
import java.util.function.Supplier;

/**
 * Holds the rules every listing shares, so the concrete creators only contain what
 * actually differs between property types.
 *
 * This is the Template Method pattern inside the Factory. {@link #create} and
 * {@link #update} fix the order of the steps and are final, so no subtype can skip
 * or reorder them. Subtypes fill in two steps: {@link #validateSpecific} (their own
 * rules) and {@link #applyTypeFields} (copying their own fields onto an entity).
 *
 * Creating and updating differ only in where the entity comes from: a new one, or
 * the existing one. That is why the type-specific step fills in a given entity
 * instead of constructing one — the same code serves both, so an update can never
 * skip a rule that creation enforces.
 *
 * @param <T> the entity subtype this creator builds
 */
public abstract class AbstractPropertyCreator<T extends Property> implements PropertyCreator {

    private final Class<T> entityClass;
    private final Supplier<T> newEntity;

    protected AbstractPropertyCreator(Class<T> entityClass, Supplier<T> newEntity) {
        this.entityClass = entityClass;
        this.newEntity = newEntity;
    }

    @Override
    public final Class<T> entityClass() {
        return entityClass;
    }

    @Override
    public final Property create(PropertyRequest request, User host) {
        TypeAttributes attributes = validate(request);

        T property = newEntity.get();
        applyTypeFields(property, attributes);
        applyCommonFields(property, request);
        property.setHost(host);
        property.setActive(true);
        enforceInvariants(property);
        return property;
    }

    /**
     * Replaces an existing listing's fields with the request's. PUT semantics: the
     * request is the complete new state, so an optional attribute left out becomes
     * "not stated". Host, id, version and active status are not touched.
     */
    @Override
    public final void update(Property existing, PropertyRequest request) {
        T property = entityClass.cast(existing);
        TypeAttributes attributes = validate(request);

        applyTypeFields(property, attributes);
        applyCommonFields(property, request);
        enforceInvariants(property);
    }

    private TypeAttributes validate(PropertyRequest request) {
        validateCommon(request);
        TypeAttributes attributes = TypeAttributes.parse(request.getAttributes(), attributeSpecs());
        validateSpecific(request, attributes);
        return attributes;
    }

    /**
     * Rules that hold for every listing regardless of type.
     *
     * Bean validation on the request checks these too, but only where a controller
     * asks for it. The factory checks again because it is the one path every listing
     * takes, whether it arrives from a form, REST, GraphQL or the demo seeder.
     */
    protected void validateCommon(PropertyRequest request) {
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
    protected abstract void validateSpecific(PropertyRequest request, TypeAttributes attributes);

    /** Copy this type's own fields onto the entity, new or existing. */
    protected abstract void applyTypeFields(T property, TypeAttributes attributes);

    /**
     * Runs last, after the shared fields are copied, for rules that override one of
     * them (a studio always has zero bedrooms). Running last means nothing can undo it.
     */
    protected void enforceInvariants(T property) {
    }

    /** Bedrooms is optional on the request and defaults to zero. */
    protected static int bedroomsOf(PropertyRequest request) {
        return request.getBedrooms() == null ? 0 : request.getBedrooms();
    }

    private void applyCommonFields(Property property, PropertyRequest request) {
        property.setTitle(strip(request.getTitle()));
        property.setDescription(request.getDescription());
        // Trimmed because city is a search key: "Goa " would never match a search for "goa".
        property.setCity(strip(request.getCity()));
        property.setCountry(strip(request.getCountry()));
        property.setAddress(request.getAddress());
        property.setPricePerNight(request.getPricePerNight());
        property.setCurrency(request.getCurrency());
        property.setMaxGuests(request.getMaxGuests());
        property.setBedrooms(bedroomsOf(request));
        property.setBathrooms(request.getBathrooms() == null ? 0 : request.getBathrooms());
        property.setAvailableUntil(request.getAvailableUntil());
    }

    private static String strip(String value) {
        return value == null ? null : value.strip();
    }
}
