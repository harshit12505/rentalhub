package com.rentalhub.web.rest;

import com.rentalhub.domain.model.enums.BookingStatus;
import com.rentalhub.domain.model.enums.Currency;
import com.rentalhub.dto.BookingRequest;
import com.rentalhub.dto.BookingView;
import com.rentalhub.service.BookingService;
import com.rentalhub.service.CurrencyService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

/**
 * Bookings over REST. As with listings, the acting user is named by the
 * {@value ApiHeaders#DEMO_USER_ID} header and every rule lives in BookingService.
 *
 * Every endpoint takes an optional {@code currency}: the total is then also shown converted
 * into it, as {@code displayTotal}. What is charged, and stored, is always {@code totalAmount}
 * in the listing's own currency.
 */
@RestController
@RequestMapping("/api")
public class BookingController {

    private final BookingService bookingService;
    private final CurrencyService currencyService;

    public BookingController(BookingService bookingService, CurrencyService currencyService) {
        this.bookingService = bookingService;
        this.currencyService = currencyService;
    }

    /**
     * Books a stay and pays for it.
     *
     * 201 Created: paid and confirmed. 202 Accepted: the booking exists and its dates are
     * held, but the payment's outcome isn't known yet (the provider's answer was lost); the
     * Location shows how it settles, within minutes. A declined card is a 402 and a payment
     * provider in trouble a 503; either way nothing was charged and the dates were released.
     */
    @PostMapping("/bookings")
    public ResponseEntity<BookingView> book(@RequestHeader(ApiHeaders.DEMO_USER_ID) long userId,
                                            @Valid @RequestBody BookingRequest request,
                                            @RequestParam(required = false) Currency currency) {
        BookingView booking = currencyService.inCurrency(bookingService.book(request, userId), currency);
        // fromCurrentRequestUri: the path without the query string, so ?currency=... isn't copied in.
        URI location = ServletUriComponentsBuilder.fromCurrentRequestUri()
                .path("/{id}").buildAndExpand(booking.id()).toUri();
        return booking.status() == BookingStatus.PENDING
                ? ResponseEntity.accepted().location(location).body(booking)
                : ResponseEntity.created(location).body(booking);
    }

    /** The acting user's own trips, latest check-in first. */
    @GetMapping("/bookings")
    public List<BookingView> myBookings(@RequestHeader(ApiHeaders.DEMO_USER_ID) long userId,
                                        @RequestParam(required = false) Currency currency) {
        return currencyService.inCurrency(bookingService.forGuest(userId), currency);
    }

    /** One booking, for its guest or the listing's host. */
    @GetMapping("/bookings/{id}")
    public BookingView get(@PathVariable long id,
                           @RequestHeader(ApiHeaders.DEMO_USER_ID) long userId,
                           @RequestParam(required = false) Currency currency) {
        return currencyService.inCurrency(bookingService.get(id, userId), currency);
    }

    /**
     * Cancelling changes a booking's state; it does not delete it. A cancelled booking stays
     * on record, and a paid one is refunded in full, so this is a POST to an action rather
     * than a DELETE. A booking whose payment is still being decided can't be cancelled yet (409).
     */
    @PostMapping("/bookings/{id}/cancel")
    public BookingView cancel(@PathVariable long id,
                              @RequestHeader(ApiHeaders.DEMO_USER_ID) long userId,
                              @RequestParam(required = false) Currency currency) {
        return currencyService.inCurrency(bookingService.cancel(id, userId), currency);
    }

    /** Every booking of one listing, for its host. */
    @GetMapping("/properties/{propertyId}/bookings")
    public List<BookingView> forListing(@PathVariable long propertyId,
                                        @RequestHeader(ApiHeaders.DEMO_USER_ID) long userId,
                                        @RequestParam(required = false) Currency currency) {
        return currencyService.inCurrency(bookingService.forListing(propertyId, userId), currency);
    }
}
