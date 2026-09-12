package com.rentalhub.domain.repository;

import com.rentalhub.domain.model.Review;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReviewRepository extends JpaRepository<Review, Long> {

    List<Review> findByPropertyIdOrderByCreatedAtDesc(Long propertyId);

    List<Review> findByAuthorId(Long authorId);
}
