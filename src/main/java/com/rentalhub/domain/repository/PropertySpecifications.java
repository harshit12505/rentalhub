package com.rentalhub.domain.repository;

import com.rentalhub.domain.model.Property;
import com.rentalhub.domain.model.enums.Currency;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

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

    /**
     * Active listings matching every filter given; a blank city counts as no filter.
     *
     * @param maxPriceByCurrency a price ceiling for each listing currency (see
     *                           service.PriceCeilings), or null for no price filter. A listing
     *                           matches if its currency has a ceiling and its price is at or
     *                           below it: {@code (currency = 'INR' AND price_per_night <= ?)
     *                           OR (currency = 'USD' AND price_per_night <= ?) OR ...}
     */
    public static Specification<Property> search(String city, Integer minGuests,
                                                 Map<Currency, BigDecimal> maxPriceByCurrency) {
        return search(city, minGuests, maxPriceByCurrency, null);
    }

    /**
     * The same filters, narrowed to a set of listings.
     *
     * This is the SQL half of the AI phase's hybrid search: the vector search proposes
     * listings that mean something like the question, and this checks them against the live
     * rows — still on the market, right city, within budget, big enough. An embedding is a
     * snapshot of a listing's words; only the database knows its price today.
     *
     * @param ids the listings to consider, or null for all of them. An empty collection
     *            matches nothing, which is what "the vector search found nothing" means
     */
    public static Specification<Property> search(String city, Integer minGuests,
                                                 Map<Currency, BigDecimal> maxPriceByCurrency,
                                                 Collection<Long> ids) {
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
        if (maxPriceByCurrency != null) {
            filters.add((root, query, cb) -> cb.or(maxPriceByCurrency.entrySet().stream()
                    .map(ceiling -> cb.and(
                            cb.equal(root.get("currency"), ceiling.getKey()),
                            cb.lessThanOrEqualTo(root.<BigDecimal>get("pricePerNight"), ceiling.getValue())))
                    .toArray(Predicate[]::new)));
        }
        if (ids != null) {
            filters.add(ids.isEmpty()
                    ? (root, query, cb) -> cb.disjunction()
                    : (root, query, cb) -> root.get("id").in(ids));
        }
        return Specification.allOf(filters);
    }
}
