package com.rentalhub.web.rest;

import com.rentalhub.domain.model.enums.BookingStatus;
import com.rentalhub.domain.model.enums.Currency;
import com.rentalhub.dto.BookingRequest;
import com.rentalhub.dto.BookingView;
import com.rentalhub.service.BookingService;
import com.rentalhub.service.CurrencyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Bookings", description = "Booking and paying for a stay, and cancelling it. The acting user is named in X-Demo-User-Id.")
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
    @Operation(summary = "Book and pay for a stay",
            description = "The dates are held, the payment is taken (Stripe test mode, or the simulator when no key is set), then the booking is confirmed. 202 means the payment outcome is not known yet and a job will settle it within minutes. A declined card is 402 and a provider outage 503; in both cases nothing was charged and the dates were released.",
            responses = {
                    @ApiResponse(responseCode = "201", description = "Paid and confirmed", content = @Content(mediaType = "application/json", examples = @ExampleObject(ApiExamples.BOOKING))),
                    @ApiResponse(responseCode = "202", description = "Held; payment outcome not known yet", content = @Content(mediaType = "application/json", examples = @ExampleObject(ApiExamples.BOOKING))),
                    @ApiResponse(responseCode = "402", description = "The card was declined", content = @Content(mediaType = "application/problem+json", examples = @ExampleObject(ApiExamples.DECLINED))),
                    @ApiResponse(responseCode = "409", description = "The dates are taken", content = @Content(mediaType = "application/problem+json", examples = @ExampleObject(ApiExamples.DATES_TAKEN))),
                    @ApiResponse(responseCode = "400", description = "A field or a booking rule is broken", content = @Content(mediaType = "application/problem+json", examples = @ExampleObject(ApiExamples.INVALID_FIELDS)))})
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json", examples = @ExampleObject(ApiExamples.BOOKING_REQUEST)))
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
    @Operation(summary = "My bookings",
            description = "The acting user's own trips, latest check-in first. Add ?currency= to also see each total converted.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "The bookings", content = @Content(mediaType = "application/json", examples = @ExampleObject(ApiExamples.BOOKINGS))),
                    @ApiResponse(responseCode = "400", description = "The X-Demo-User-Id header is missing", content = @Content(mediaType = "application/problem+json", examples = @ExampleObject(ApiExamples.INVALID_FIELDS)))})
    @GetMapping("/bookings")
    public List<BookingView> myBookings(@RequestHeader(ApiHeaders.DEMO_USER_ID) long userId,
                                        @RequestParam(required = false) Currency currency) {
        return currencyService.inCurrency(bookingService.forGuest(userId), currency);
    }

    /** One booking, for its guest or the listing's host. */
    @Operation(summary = "One booking",
            description = "For its guest, or the host of the listing.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "The booking", content = @Content(mediaType = "application/json", examples = @ExampleObject(ApiExamples.BOOKING))),
                    @ApiResponse(responseCode = "403", description = "Neither the guest nor the host", content = @Content(mediaType = "application/problem+json", examples = @ExampleObject(ApiExamples.NOT_ALLOWED))),
                    @ApiResponse(responseCode = "404", description = "There is no such booking", content = @Content(mediaType = "application/problem+json", examples = @ExampleObject(ApiExamples.NOT_FOUND)))})
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
    @Operation(summary = "Cancel a booking",
            description = "By its guest or the listing's host, until check-in day. The dates are freed, and a paid booking is refunded in full. Cancelling twice changes nothing.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "The cancelled booking", content = @Content(mediaType = "application/json", examples = @ExampleObject(ApiExamples.CANCELLED_BOOKING))),
                    @ApiResponse(responseCode = "409", description = "The stay has started, or the payment is still being decided", content = @Content(mediaType = "application/problem+json", examples = @ExampleObject(ApiExamples.DATES_TAKEN)))})
    @PostMapping("/bookings/{id}/cancel")
    public BookingView cancel(@PathVariable long id,
                              @RequestHeader(ApiHeaders.DEMO_USER_ID) long userId,
                              @RequestParam(required = false) Currency currency) {
        return currencyService.inCurrency(bookingService.cancel(id, userId), currency);
    }

    /** Every booking of one listing, for its host. */
    @Operation(summary = "Bookings of one listing",
            description = "For the listing's host, in check-in order.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "The bookings", content = @Content(mediaType = "application/json", examples = @ExampleObject(ApiExamples.BOOKINGS))),
                    @ApiResponse(responseCode = "403", description = "Not the host of this listing", content = @Content(mediaType = "application/problem+json", examples = @ExampleObject(ApiExamples.NOT_ALLOWED)))})
    @GetMapping("/properties/{propertyId}/bookings")
    public List<BookingView> forListing(@PathVariable long propertyId,
                                        @RequestHeader(ApiHeaders.DEMO_USER_ID) long userId,
                                        @RequestParam(required = false) Currency currency) {
        return currencyService.inCurrency(bookingService.forListing(propertyId, userId), currency);
    }
}
