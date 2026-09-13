package com.rentalhub.domain.repository;

import com.rentalhub.domain.model.PropertyImage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface PropertyImageRepository extends JpaRepository<PropertyImage, Long> {

    /**
     * Image URLs for many listings in one query, ordered so the first row for each
     * listing is its cover. Returns plain values (a projection), not entities, so
     * reading the listing id never touches a lazy association.
     */
    @Query("""
            SELECT i.property.id AS propertyId, i.url AS url
            FROM PropertyImage i
            WHERE i.property.id IN :propertyIds
            ORDER BY i.property.id, i.sortOrder
            """)
    List<ImageUrl> findImageUrls(@Param("propertyIds") Collection<Long> propertyIds);

    interface ImageUrl {
        Long getPropertyId();

        String getUrl();
    }
}
