package com.rentalhub.domain.repository;

import com.rentalhub.domain.model.Property;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface PropertyRepository extends JpaRepository<Property, Long>, JpaSpecificationExecutor<Property> {

    List<Property> findByHostId(Long hostId);

    /**
     * The structured half of search. The AI phase reuses these same filters
     * alongside vector similarity to build the hybrid query.
     * Every filter is optional: pass null to skip it. See PropertySpecifications
     * for why this is built dynamically rather than written as one JPQL string.
     */
    default Page<Property> search(String city, Integer minGuests, BigDecimal maxPrice, Pageable pageable) {
        return findAll(PropertySpecifications.search(city, minGuests, maxPrice), pageable);
    }

    /** Feeds the scheduled job that retires expired listings. */
    List<Property> findByActiveTrueAndAvailableUntilBefore(LocalDate date);
}
