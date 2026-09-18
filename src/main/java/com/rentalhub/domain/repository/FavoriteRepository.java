package com.rentalhub.domain.repository;

import com.rentalhub.domain.model.Favorite;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface FavoriteRepository extends JpaRepository<Favorite, Long> {

    List<Favorite> findByUserId(Long userId);

    /**
     * A user's saved listings, newest first, with the listing fetched in the same query: the
     * favourites list and the preference profile both read the listing itself, and nothing can
     * be lazy-loaded once the service returns (open-in-view is off).
     */
    @EntityGraph(attributePaths = "property")
    List<Favorite> findWithPropertyByUserIdOrderByCreatedAtDescIdDesc(Long userId);

    /** Just the ids: what the taste vector and the statistics are built from. */
    @Query("SELECT f.property.id FROM Favorite f WHERE f.user.id = :userId")
    List<Long> findPropertyIdsByUserId(@Param("userId") Long userId);

    boolean existsByUserIdAndPropertyId(Long userId, Long propertyId);

    void deleteByUserIdAndPropertyId(Long userId, Long propertyId);
}
