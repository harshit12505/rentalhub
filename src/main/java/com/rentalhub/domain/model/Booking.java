package com.rentalhub.domain.model;

import com.rentalhub.domain.model.enums.BookingStatus;
import com.rentalhub.domain.model.enums.Currency;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.envers.Audited;
import org.hibernate.envers.RelationTargetAuditMode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * A stay at a listing. Audited by Envers, so bookings_aud records every status change
 * (confirmed, cancelled, ...) along with who made it.
 */
@Entity
@Table(name = "bookings")
@Audited
@Getter
@Setter
@NoArgsConstructor
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Setter(AccessLevel.NONE)
    private Long id;

    @Version
    @Column(nullable = false)
    @Setter(AccessLevel.NONE)
    private Long version;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "property_id", nullable = false)
    private Property property;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "guest_id", nullable = false)
    @Audited(targetAuditMode = RelationTargetAuditMode.NOT_AUDITED)
    private User guest;

    @Column(name = "check_in", nullable = false)
    private LocalDate checkIn;

    @Column(name = "check_out", nullable = false)
    private LocalDate checkOut;

    @Column(nullable = false)
    private Integer guests;

    /**
     * What the guest is actually charged, in the property's own currency.
     * We never store a converted figure: exchange rates move, stored
     * conversions go stale, and then the books stop balancing.
     */
    @Column(name = "total_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3)
    private Currency currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BookingStatus status = BookingStatus.PENDING;

    @Column(name = "payment_reference", length = 120)
    private String paymentReference;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private Instant createdAt;

    /**
     * A booking for these dates, priced from the listing as it stands right now: the
     * nightly price times the number of nights, in the listing's own currency.
     * Status is left at PENDING; the caller decides when the booking is confirmed.
     */
    public static Booking reserve(Property property, User guest, LocalDate checkIn, LocalDate checkOut, int guests) {
        Booking booking = new Booking();
        booking.property = property;
        booking.guest = guest;
        booking.checkIn = checkIn;
        booking.checkOut = checkOut;
        booking.guests = guests;
        booking.currency = property.getCurrency();
        // Exact: a price never has more decimals than its currency (the factory refuses
        // it), so round() only fixes the scale, e.g. 7500.0000 → 7500.00.
        booking.totalAmount = booking.currency.round(
                property.getPricePerNight().multiply(BigDecimal.valueOf(booking.nights())));
        return booking;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    /** Nights stayed. Check-out day is not charged. */
    public long nights() {
        return ChronoUnit.DAYS.between(checkIn, checkOut);
    }
}
