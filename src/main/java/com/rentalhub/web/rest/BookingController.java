package com.rentalhub.web.rest;

import com.rentalhub.dto.BookingRequest;
import com.rentalhub.dto.BookingView;
import com.rentalhub.service.BookingService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

/**
 * Bookings over REST. As with listings, the acting user is named by the
 * {@value ApiHeaders#DEMO_USER_ID} header and every rule lives in BookingService.
 */
@RestController
@RequestMapping("/api")
public class BookingController {

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @PostMapping("/bookings")
    public ResponseEntity<BookingView> book(@RequestHeader(ApiHeaders.DEMO_USER_ID) long userId,
                                            @Valid @RequestBody BookingRequest request) {
        BookingView booking = bookingService.book(request, userId);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(booking.id()).toUri();
        return ResponseEntity.created(location).body(booking);
    }

    /** The acting user's own trips, latest check-in first. */
    @GetMapping("/bookings")
    public List<BookingView> myBookings(@RequestHeader(ApiHeaders.DEMO_USER_ID) long userId) {
        return bookingService.forGuest(userId);
    }

    /** One booking, for its guest or the listing's host. */
    @GetMapping("/bookings/{id}")
    public BookingView get(@PathVariable long id, @RequestHeader(ApiHeaders.DEMO_USER_ID) long userId) {
        return bookingService.get(id, userId);
    }

    /**
     * Cancelling changes a booking's state; it does not delete it. A cancelled booking
     * stays on record (and from phase 5 may carry a refund), so this is a POST to an
     * action rather than a DELETE.
     */
    @PostMapping("/bookings/{id}/cancel")
    public BookingView cancel(@PathVariable long id, @RequestHeader(ApiHeaders.DEMO_USER_ID) long userId) {
        return bookingService.cancel(id, userId);
    }

    /** Every booking of one listing, for its host. */
    @GetMapping("/properties/{propertyId}/bookings")
    public List<BookingView> forListing(@PathVariable long propertyId,
                                        @RequestHeader(ApiHeaders.DEMO_USER_ID) long userId) {
        return bookingService.forListing(propertyId, userId);
    }
}
