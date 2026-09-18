package com.rentalhub.web.graphql;

import com.rentalhub.dto.BookingRequest;
import com.rentalhub.dto.BookingView;
import com.rentalhub.dto.ReviewRequest;
import com.rentalhub.dto.ReviewView;
import com.rentalhub.service.BookingService;
import com.rentalhub.service.ReviewService;
import jakarta.validation.Valid;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.ContextValue;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.stereotype.Controller;

/**
 * Booking and reviewing over GraphQL.
 *
 * The inputs are bound onto the very same request classes as REST (BookingRequest,
 * ReviewRequest), so {@code @Valid} applies the same bean-validation rules with the same
 * translated messages, and the services apply the same business rules after that. A booking
 * is paid for exactly as over REST (the saga of phase 5): one whose payment outcome is not yet
 * known comes back with status PENDING rather than as an error.
 */
@Controller
public class BookingGraphQlController {

    private final BookingService bookings;
    private final ReviewService reviews;

    public BookingGraphQlController(BookingService bookings, ReviewService reviews) {
        this.bookings = bookings;
        this.reviews = reviews;
    }

    @MutationMapping
    public BookingView createBooking(@Argument @Valid BookingRequest input,
                                     @ContextValue(name = DemoUserInterceptor.USER_ID, required = false) Long userId) {
        return bookings.book(input, DemoUserInterceptor.required(userId));
    }

    @MutationMapping
    public BookingView cancelBooking(@Argument long id,
                                     @ContextValue(name = DemoUserInterceptor.USER_ID, required = false) Long userId) {
        return bookings.cancel(id, DemoUserInterceptor.required(userId));
    }

    @MutationMapping
    public ReviewView createReview(@Argument long propertyId,
                                   @Argument @Valid ReviewRequest input,
                                   @ContextValue(name = DemoUserInterceptor.USER_ID, required = false) Long userId) {
        return reviews.create(propertyId, input, DemoUserInterceptor.required(userId));
    }
}
