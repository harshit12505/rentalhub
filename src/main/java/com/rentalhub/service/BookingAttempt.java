package com.rentalhub.service;

import com.rentalhub.domain.model.Booking;
import com.rentalhub.domain.model.Property;
import com.rentalhub.domain.model.User;
import com.rentalhub.domain.model.enums.BookingStatus;
import com.rentalhub.domain.repository.BookingRepository;
import com.rentalhub.domain.repository.PropertyRepository;
import com.rentalhub.domain.repository.UserRepository;
import com.rentalhub.dto.BookingRequest;
import com.rentalhub.dto.BookingView;
import com.rentalhub.exception.ConflictException;
import com.rentalhub.exception.OperationNotAllowedException;
import com.rentalhub.exception.ResourceNotFoundException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * One attempt at placing a booking, in one database transaction.
 *
 * It is a bean of its own, apart from BookingService, on purpose. {@code @Transactional}
 * works through a proxy that Spring wraps around the bean, and a call from inside the
 * same class ({@code this.place(...)}) never passes through that proxy, so it would get
 * no transaction. Because BookingService calls this bean from outside, every retry it
 * makes is a genuinely new transaction that reads the listing afresh.
 */
@Component
class BookingAttempt {

    private final PropertyRepository properties;
    private final UserRepository users;
    private final BookingRepository bookings;
    private final BookingRules rules;
    private final ApplicationEventPublisher events;

    BookingAttempt(PropertyRepository properties,
                   UserRepository users,
                   BookingRepository bookings,
                   BookingRules rules,
                   ApplicationEventPublisher events) {
        this.properties = properties;
        this.users = users;
        this.bookings = bookings;
        this.rules = rules;
        this.events = events;
    }

    /**
     * Checks the request against the listing and inserts the booking, or throws.
     *
     * The listing is read with OPTIMISTIC_FORCE_INCREMENT (see
     * PropertyRepository.findForBookingById). As this transaction commits, Hibernate
     * raises the listing's version, and that fails if any other booking or edit of the
     * listing committed after we read it. The failure rolls back everything done here,
     * the booking row included.
     */
    @Transactional
    public BookingView place(BookingRequest request, long guestId) {
        long propertyId = request.getPropertyId();
        Property property = properties.findForBookingById(propertyId)
                .orElseThrow(() -> new ResourceNotFoundException("property.notFound", propertyId));
        User guest = users.findById(guestId)
                .orElseThrow(() -> new ResourceNotFoundException("user.notFound", guestId));
        if (property.getHost().getId() == guestId) {
            throw new OperationNotAllowedException("booking.ownListing");
        }
        rules.checkListing(property, request.getGuests(), request.getCheckOut());

        // The friendly answer for the everyday case: someone booked these dates earlier.
        // It cannot be the guarantee, because two transactions can both run this check
        // before either has inserted anything. The version bump and the database's
        // exclusion constraint are the guarantee.
        if (bookings.countOverlapping(propertyId, request.getCheckIn(), request.getCheckOut(), BookingStatus.LIVE) > 0) {
            throw new ConflictException("booking.dates.unavailable");
        }

        // PENDING and UNPAID. The dates are held from the moment this commits (the overlap
        // constraint counts PENDING bookings), and PaymentService takes the payment after
        // that, outside this transaction.
        Booking booking = Booking.reserve(property, guest, request.getCheckIn(), request.getCheckOut(),
                request.getGuests());
        // The id comes from an IDENTITY column, so Hibernate runs the INSERT right away:
        // an overlap the database refuses shows up here, not later at commit.
        bookings.save(booking);

        events.publishEvent(new ListingBookedEvent(propertyId));
        return BookingViews.toView(booking);
    }
}
