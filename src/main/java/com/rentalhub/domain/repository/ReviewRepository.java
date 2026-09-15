package com.rentalhub.domain.repository;

import com.rentalhub.domain.model.Review;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Reviews. The finders that feed a view fetch the author in the same query, because the
 * view shows the author's name and nothing can be lazy-loaded after the service returns.
 */
public interface ReviewRepository extends JpaRepository<Review, Long> {

    /** A listing's reviews, newest first; id breaks ties so the order is stable. */
    @EntityGraph(attributePaths = "author")
    List<Review> findByPropertyIdOrderByCreatedAtDescIdDesc(Long propertyId);

    @EntityGraph(attributePaths = "author")
    Optional<Review> findWithAuthorById(Long id);

    boolean existsByPropertyIdAndAuthorId(Long propertyId, Long authorId);

    List<Review> findByAuthorId(Long authorId);
}
