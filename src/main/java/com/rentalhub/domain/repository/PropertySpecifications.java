package com.rentalhub.domain.repository;

import com.rentalhub.domain.model.Property;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds listing-search queries from whichever filters were actually given.
 *
 * The obvious JPQL, {@code (:city IS NULL OR LOWER(p.city) = LOWER(:city))}, fails on
 * PostgreSQL: a null parameter that is only ever compared with IS NULL has no type
 * Postgres can infer, so it is bound as bytea and {@code lower(bytea)} does not exist.
 * It is also slow: a WHERE clause full of "or the filter is null" branches stops the
 * planner from reliably using the lower(city) and price indexes.
 *
 * Adding a predicate only for each filter that is present fixes both. The SQL for a
 * city-only search is just {@code WHERE active AND lower(city) = lower(?)}.
 */
public final class PropertySpecifications {

    private PropertySpecifications() {
    }

    /** Active listings matching every non-null filter; a blank city counts as no filter. */
    public static Specification<Property> search(String city, Integer minGuests, BigDecimal maxPrice) {
        List<Specification<Property>> filters = new ArrayList<>();
        filters.add((root, query, cb) -> cb.isTrue(root.get("active")));

        if (city != null && !city.isBlank()) {
            String wanted = city.strip();
            // Postgres lowers both sides, so the comparison matches the lower(city) index exactly.
            filters.add((root, query, cb) -> cb.equal(cb.lower(root.get("city")), cb.lower(cb.literal(wanted))));
        }
        if (minGuests != null) {
            filters.add((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("maxGuests"), minGuests));
        }
        if (maxPrice != null) {
            filters.add((root, query, cb) -> cb.lessThanOrEqualTo(root.get("pricePerNight"), maxPrice));
        }
        return Specification.allOf(filters);
    }
}
