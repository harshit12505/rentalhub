package com.rentalhub.domain.repository;

import com.rentalhub.domain.model.Booking;
import com.rentalhub.domain.model.enums.BookingStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Bookings. The finders that feed a view fetch the listing and the guest in the same
 * query (an entity graph), because the view shows both and, with open-in-view off,
 * nothing can be lazy-loaded after the service returns.
 */
public interface BookingRepository extends JpaRepository<Booking, Long> {

    @EntityGraph(attributePaths = {"property", "guest"})
    Optional<Booking> findWithDetailsById(Long id);

    /** A guest's trips, latest check-in first. */
    @EntityGraph(attributePaths = {"property", "guest"})
    List<Booking> findByGuestIdOrderByCheckInDescIdDesc(Long guestId);

    /** Every booking of one listing, for its host, in check-in order. */
    @EntityGraph(attributePaths = {"property", "guest"})
    List<Booking> findByPropertyIdOrderByCheckInAscIdAsc(Long propertyId);

    /** Any booking at all, whatever its status: a listing with history must not be deleted. */
    boolean existsByPropertyId(Long propertyId);

    /**
     * Does a live booking already overlap these dates?
     * Two ranges overlap when each one starts before the other ends.
     *
     * This is the application's quick check, which gives the everyday "already booked"
     * answer. It cannot see bookings other transactions have not committed yet, so it is
     * not the guarantee; the no_overlapping_bookings constraint is.
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
