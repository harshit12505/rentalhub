package com.rentalhub.domain.model;

import com.rentalhub.domain.model.enums.BookingStatus;
import com.rentalhub.domain.model.enums.Currency;
import com.rentalhub.domain.model.enums.PaymentProvider;
import com.rentalhub.domain.model.enums.PaymentStatus;
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
 * A stay at a listing. Audited by Envers, so bookings_aud records every status and payment
 * change (held, paid, cancelled, refunded, ...) along with who made it.
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

    /**
     * Where the money stands (see PaymentStatus). This and the three fields after it change
     * only through the payment methods below, which refuse a step out of order.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false, length = 20)
    @Setter(AccessLevel.NONE)
    private PaymentStatus paymentStatus = PaymentStatus.UNPAID;

    /** Who holds the money; null until a payment is started. A refund goes back the same way. */
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_provider", length = 20)
    @Setter(AccessLevel.NONE)
    private PaymentProvider paymentProvider;

    /** The provider's id for the payment, such as a Stripe PaymentIntent's pi_... */
    @Column(name = "payment_reference", length = 120)
    @Setter(AccessLevel.NONE)
    private String paymentReference;

    /** The provider's id for the refund, once there is one. */
    @Column(name = "refund_reference", length = 120)
    @Setter(AccessLevel.NONE)
    private String refundReference;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private Instant createdAt;

    /**
     * A booking for these dates, priced from the listing as it stands right now: the
     * nightly price times the number of nights, in the listing's own currency.
     * PENDING and UNPAID: the dates are held while the payment is taken.
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

    /**
     * The provider has a payment for this booking, and no money has moved yet. Recorded and
     * committed before the payment is confirmed, so whatever happens next, the booking says
     * which payment to ask about.
     */
    public void paymentStarted(PaymentProvider provider, String reference) {
        requireState(BookingStatus.PENDING, PaymentStatus.UNPAID);
        this.paymentProvider = provider;
        this.paymentReference = reference;
    }

    /** The money was taken: the stay is on. */
    public void paid() {
        requireState(BookingStatus.PENDING, PaymentStatus.UNPAID);
        this.status = BookingStatus.CONFIRMED;
        this.paymentStatus = PaymentStatus.PAID;
    }

    /**
     * The payment did not go through. The booking is cancelled rather than deleted: its dates
     * are free at once (the overlap constraint ignores cancelled bookings), and the record
     * stays, because the provider's payment points at it.
     */
    public void paymentFailed() {
        requireState(BookingStatus.PENDING, PaymentStatus.UNPAID);
        this.status = BookingStatus.CANCELLED;
        this.paymentStatus = PaymentStatus.FAILED;
    }

    /** Cancels a confirmed stay. A paid booking stays PAID until its refund has gone through. */
    public void cancel() {
        if (status != BookingStatus.CONFIRMED) {
            throw new IllegalStateException("Booking " + id + " is " + status + "; only a confirmed booking can be cancelled");
        }
        this.status = BookingStatus.CANCELLED;
    }

    /** Cancelled, but the guest's money has not gone back yet. */
    public boolean refundOwed() {
        return status == BookingStatus.CANCELLED && paymentStatus == PaymentStatus.PAID;
    }

    /**
     * The money went back to the guest.
     *
     * @param refundReference the provider's id for the refund; null if it was refunded outside
     *                        RentalHub (from the provider's dashboard, say)
     */
    public void refunded(String refundReference) {
        requireState(BookingStatus.CANCELLED, PaymentStatus.PAID);
        this.paymentStatus = PaymentStatus.REFUNDED;
        this.refundReference = refundReference;
    }

    private void requireState(BookingStatus expectedStatus, PaymentStatus expectedPayment) {
        if (status != expectedStatus || paymentStatus != expectedPayment) {
            throw new IllegalStateException("Booking " + id + " is " + status + "/" + paymentStatus
                    + ", but this step needs " + expectedStatus + "/" + expectedPayment);
        }
    }

    @PrePersist
    void onCreate() {
        // Postgres keeps microseconds. Cutting the rest off here makes the value in memory
        // exactly the value stored, so anything derived from it (the payment idempotency keys)
        // is the same before and after the booking is read back.
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    /** Nights stayed. Check-out day is not charged. */
    public long nights() {
        return ChronoUnit.DAYS.between(checkIn, checkOut);
    }
}
