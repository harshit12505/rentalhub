package com.rentalhub.service;

import com.rentalhub.domain.model.Property;
import com.rentalhub.domain.model.Review;
import com.rentalhub.domain.model.User;
import com.rentalhub.domain.model.enums.BookingStatus;
import com.rentalhub.domain.repository.BookingRepository;
import com.rentalhub.domain.repository.PropertyRepository;
import com.rentalhub.domain.repository.ReviewRepository;
import com.rentalhub.domain.repository.UserRepository;
import com.rentalhub.dto.ReviewRequest;
import com.rentalhub.dto.ReviewView;
import com.rentalhub.exception.ConflictException;
import com.rentalhub.exception.OperationNotAllowedException;
import com.rentalhub.exception.ResourceNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Reviews: guests rate and describe listings they have stayed at.
 *
 * The rules:
 * <ul>
 *   <li>Only a guest whose stay at the listing has ended may review it: a confirmed (or
 *       completed) booking whose check-out day has come. Cancelled stays don't count.
 *       This is what stops fake reviews, and it also rules out hosts reviewing their own
 *       listings, since they can't book them.</li>
 *   <li>One review per guest per listing; the author can edit or delete it. The service
 *       checks first, for a friendly answer, and a unique constraint (V2) makes it true
 *       even for two requests at the same moment.</li>
 * </ul>
 * Reviews are audited (reviews_aud keeps every edit) and not cached.
 */
@Slf4j
@Service
public class ReviewService {

    /** Bookings that count as a stay. */
    private static final Set<BookingStatus> STAYED = EnumSet.of(BookingStatus.CONFIRMED, BookingStatus.COMPLETED);

    private static final String ONE_REVIEW_PER_GUEST = "uq_review_author_property";

    private final ReviewRepository reviews;
    private final PropertyRepository properties;
    private final UserRepository users;
    private final BookingRepository bookings;
    private final Clock clock;

    public ReviewService(ReviewRepository reviews,
                         PropertyRepository properties,
                         UserRepository users,
                         BookingRepository bookings,
                         Clock clock) {
        this.reviews = reviews;
        this.properties = properties;
        this.users = users;
        this.bookings = bookings;
        this.clock = clock;
    }

    @Transactional
    public ReviewView create(long propertyId, ReviewRequest request, long authorId) {
        Property property = properties.findById(propertyId)
                .orElseThrow(() -> new ResourceNotFoundException("property.notFound", propertyId));
        User author = users.findById(authorId)
                .orElseThrow(() -> new ResourceNotFoundException("user.notFound", authorId));
        boolean stayed = bookings.existsByPropertyIdAndGuestIdAndStatusInAndCheckOutLessThanEqual(
                propertyId, authorId, STAYED, LocalDate.now(clock));
        if (!stayed) {
            throw new OperationNotAllowedException("review.notStayed");
        }
        if (reviews.existsByPropertyIdAndAuthorId(propertyId, authorId)) {
            throw new ConflictException("review.alreadyReviewed");
        }

        Review review;
        try {
            // The id comes from an IDENTITY column, so the INSERT, and the unique check, run now.
            review = reviews.save(new Review(property, author, request.getRating(), clean(request.getComment())));
        } catch (DataIntegrityViolationException e) {
            // Our check said no review yet, but another request inserted one a moment ago.
            if (ConstraintViolations.violated(e, ConstraintViolations.UNIQUE_VIOLATION, ONE_REVIEW_PER_GUEST)) {
                throw new ConflictException("review.alreadyReviewed");
            }
            throw e;
        }

        log.atInfo().setMessage("review.created")
                .addKeyValue("reviewId", review.getId())
                .addKeyValue("propertyId", propertyId)
                .addKeyValue("authorId", authorId)
                .addKeyValue("rating", review.getRating())
                .log();
        return toView(review);
    }

    @Transactional(readOnly = true)
    public ReviewView get(long reviewId) {
        return toView(load(reviewId));
    }

    /** A listing's reviews, newest first. */
    @Transactional(readOnly = true)
    public List<ReviewView> forListing(long propertyId) {
        if (!properties.existsById(propertyId)) {
            throw new ResourceNotFoundException("property.notFound", propertyId);
        }
        return reviews.findByPropertyIdOrderByCreatedAtDescIdDesc(propertyId).stream()
                .map(ReviewService::toView)
                .toList();
    }

    /** Replaces the rating and comment. Only the author may. */
    @Transactional
    public ReviewView update(long reviewId, ReviewRequest request, long actingUserId) {
        Review review = loadWrittenBy(reviewId, actingUserId);
        int previousRating = review.getRating();
        review.setRating(request.getRating());
        review.setComment(clean(request.getComment()));
        reviews.flush();

        log.atInfo().setMessage("review.updated")
                .addKeyValue("reviewId", reviewId)
                .addKeyValue("propertyId", review.getProperty().getId())
                .addKeyValue("authorId", actingUserId)
                .addKeyValue("previousRating", previousRating)
                .addKeyValue("rating", review.getRating())
                .log();
        return toView(review);
    }

    /** Deletes a review. Only the author may; its history stays in reviews_aud. */
    @Transactional
    public void delete(long reviewId, long actingUserId) {
        Review review = loadWrittenBy(reviewId, actingUserId);
        reviews.delete(review);

        log.atInfo().setMessage("review.deleted")
                .addKeyValue("reviewId", reviewId)
                .addKeyValue("propertyId", review.getProperty().getId())
                .addKeyValue("authorId", actingUserId)
                .log();
    }

    private Review load(long reviewId) {
        return reviews.findWithAuthorById(reviewId)
                .orElseThrow(() -> new ResourceNotFoundException("review.notFound", reviewId));
    }

    private Review loadWrittenBy(long reviewId, long actingUserId) {
        Review review = load(reviewId);
        if (review.getAuthor().getId() != actingUserId) {
            throw new OperationNotAllowedException("review.notAuthor");
        }
        return review;
    }

    /** Surrounding spaces removed; an empty comment is no comment. */
    private static String clean(String comment) {
        if (comment == null || comment.isBlank()) {
            return null;
        }
        return comment.strip();
    }

    private static ReviewView toView(Review review) {
        User author = review.getAuthor();
        return new ReviewView(
                review.getId(),
                review.getProperty().getId(),
                new ReviewView.Author(author.getId(), author.getFullName()),
                review.getRating(),
                review.getComment(),
                review.getCreatedAt());
    }
}
