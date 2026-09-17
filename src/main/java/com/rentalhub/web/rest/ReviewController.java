package com.rentalhub.web.rest;

import com.rentalhub.dto.ReviewRequest;
import com.rentalhub.dto.ReviewView;
import com.rentalhub.service.ReviewService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

/**
 * Reviews over REST. A review is written for a listing (so it is created under the
 * listing's URL) and, once it exists, is changed at its own URL. Every rule lives in
 * ReviewService.
 */
@RestController
@RequestMapping("/api")
public class ReviewController {

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @PostMapping("/properties/{propertyId}/reviews")
    public ResponseEntity<ReviewView> create(@PathVariable long propertyId,
                                             @RequestHeader(ApiHeaders.DEMO_USER_ID) long userId,
                                             @Valid @RequestBody ReviewRequest request) {
        ReviewView review = reviewService.create(propertyId, request, userId);
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/reviews/{id}").buildAndExpand(review.id()).toUri();
        return ResponseEntity.created(location).body(review);
    }

    /** A listing's reviews, newest first. Anyone may read them. */
    @GetMapping("/properties/{propertyId}/reviews")
    public List<ReviewView> forListing(@PathVariable long propertyId) {
        return reviewService.forListing(propertyId);
    }

    @GetMapping("/reviews/{id}")
    public ReviewView get(@PathVariable long id) {
        return reviewService.get(id);
    }

    /** Full replacement of the rating and comment, by the review's author. */
    @PutMapping("/reviews/{id}")
    public ReviewView update(@PathVariable long id,
                             @RequestHeader(ApiHeaders.DEMO_USER_ID) long userId,
                             @Valid @RequestBody ReviewRequest request) {
        return reviewService.update(id, request, userId);
    }

    @DeleteMapping("/reviews/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id, @RequestHeader(ApiHeaders.DEMO_USER_ID) long userId) {
        reviewService.delete(id, userId);
    }
}
