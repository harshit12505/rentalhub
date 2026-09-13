package com.rentalhub.domain.repository;

import com.rentalhub.domain.model.Booking;
import com.rentalhub.domain.model.enums.BookingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    List<Booking> findByGuestIdOrderByCheckInDesc(Long guestId);

    /** Any booking at all, whatever its status: a listing with history must not be deleted. */
    boolean existsByPropertyId(Long propertyId);

    /**
     * Does a live booking already overlap these dates?
     * Two ranges overlap when each one starts before the other ends.
     */
    @Query("""
            SELECT COUNT(b) FROM Booking b
            WHERE b.property.id = :propertyId
              AND b.status IN :liveStatuses
              AND b.checkIn < :checkOut
              AND b.checkOut > :checkIn
            """)
    long countOverlapping(@Param("propertyId") Long propertyId,
                          @Param("checkIn") LocalDate checkIn,
                          @Param("checkOut") LocalDate checkOut,
                          @Param("liveStatuses") Collection<BookingStatus> liveStatuses);
}
