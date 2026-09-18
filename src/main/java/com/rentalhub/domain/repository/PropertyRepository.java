package com.rentalhub.domain.repository;

import com.rentalhub.domain.model.Property;
import com.rentalhub.domain.model.enums.Currency;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface PropertyRepository extends JpaRepository<Property, Long>, JpaSpecificationExecutor<Property> {

    /**
     * One listing together with its host and images, in a single query. An entity
     * graph says "fetch these associations now", which the detail view needs because
     * nothing can be lazy-loaded once the repository's transaction has closed.
     */
    @EntityGraph(attributePaths = {"host", "images"})
    Optional<Property> findWithDetailsById(Long id);

    /**
     * Loads a listing in order to book it.
     *
     * OPTIMISTIC_FORCE_INCREMENT makes Hibernate raise the listing's version as the
     * booking's transaction commits ({@code UPDATE properties SET version = v + 1 WHERE
     * id = ? AND version = v}), although nothing on the listing itself changed. If another
     * transaction raised it first (another booking, or the host saving an edit), that
     * UPDATE matches no row and the commit fails with an optimistic-locking error. So the
     * bookings and edits of one listing take turns, and nothing is locked while a booking
     * is being checked.
     */
    @Lock(LockModeType.OPTIMISTIC_FORCE_INCREMENT)
    Optional<Property> findForBookingById(Long id);

    List<Property> findByHostId(Long hostId);

    /**
     * The structured half of search. The AI phase reuses these same filters
     * alongside vector similarity to build the hybrid query.
     * Every filter is optional: pass null to skip it. See PropertySpecifications
     * for why this is built dynamically rather than written as one JPQL string.
     *
     * @param maxPriceByCurrency one price ceiling per listing currency, so listings in
     *                           different currencies are compared fairly (see PriceCeilings)
     */
    default Page<Property> search(String city, Integer minGuests, Map<Currency, BigDecimal> maxPriceByCurrency,
                                  Pageable pageable) {
        return findAll(PropertySpecifications.search(city, minGuests, maxPriceByCurrency), pageable);
    }

    /**
     * Listings still on the market whose last available day is before {@code date}: the
     * work list of StaleListingJob. Ids only, because the job changes each listing
     * through PropertyService, in a transaction of its own.
     */
    @Query("SELECT p.id FROM Property p WHERE p.active = true AND p.availableUntil < :date ORDER BY p.id")
    List<Long> findActiveIdsAvailableUntilBefore(@Param("date") LocalDate date);

    /**
     * The cities that have a listing on the market, so the query parser only recognises a
     * city that someone could actually stay in. Small, and read once per question.
     */
    @Query("SELECT DISTINCT p.city FROM Property p WHERE p.active = true ORDER BY p.city")
    List<String> findDistinctActiveCities();

    /**
     * Candidate listings for the AI phase's hybrid search: still on the market, matching the
     * filters, and not the asker's own. {@code ids} narrows them to what the vector search
     * proposed; null means "all of them", for the case where there is no vector search.
     *
     * Newest first, and always limited, so a question can never drag the whole table into
     * memory. The caller re-orders what comes back by similarity.
     */
    default List<Property> searchCandidates(String city, Integer minGuests,
                                            Map<Currency, BigDecimal> maxPriceByCurrency,
                                            Collection<Long> ids, Long notHostedBy, int limit) {
        Specification<Property> candidates = PropertySpecifications.search(city, minGuests, maxPriceByCurrency, ids);
        if (notHostedBy != null) {
            // The host's foreign key column, so this costs no join.
            candidates = candidates.and((root, query, cb) -> cb.notEqual(root.get("host").get("id"), notHostedBy));
        }
        return findAll(candidates, PageRequest.of(0, limit,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")))).getContent();
    }
}
