package com.rentalhub.web.rest;

import com.rentalhub.dto.ReviewRequest;
import com.rentalhub.dto.ReviewView;
import com.rentalhub.service.ReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Reviews", description = "Reviews of listings, by guests whose stay there has ended. One review per guest per listing.")
@RestController
@RequestMapping("/api")
public class ReviewController {

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @Operation(summary = "Review a listing",
            description = "Only a guest whose confirmed stay there has ended, once.",
            responses = {
                    @ApiResponse(responseCode = "201", description = "Created; the Location header points at it", content = @Content(mediaType = "application/json", examples = @ExampleObject(ApiExamples.REVIEW))),
                    @ApiResponse(responseCode = "403", description = "No finished stay there", content = @Content(mediaType = "application/problem+json", examples = @ExampleObject(ApiExamples.NOT_ALLOWED))),
                    @ApiResponse(responseCode = "400", description = "The rating is not 1 to 5", content = @Content(mediaType = "application/problem+json", examples = @ExampleObject(ApiExamples.INVALID_FIELDS)))})
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json", examples = @ExampleObject(ApiExamples.REVIEW_REQUEST)))
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
    @Operation(summary = "A listing's reviews",
            description = "Newest first. Anyone may read them.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "The reviews", content = @Content(mediaType = "application/json", examples = @ExampleObject(ApiExamples.REVIEWS)))})
    @GetMapping("/properties/{propertyId}/reviews")
    public List<ReviewView> forListing(@PathVariable long propertyId) {
        return reviewService.forListing(propertyId);
    }

    @Operation(summary = "One review",
            description = "Anyone may read it.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "The review", content = @Content(mediaType = "application/json", examples = @ExampleObject(ApiExamples.REVIEW))),
                    @ApiResponse(responseCode = "404", description = "There is no such review", content = @Content(mediaType = "application/problem+json", examples = @ExampleObject(ApiExamples.NOT_FOUND)))})
    @GetMapping("/reviews/{id}")
    public ReviewView get(@PathVariable long id) {
        return reviewService.get(id);
    }

    /** Full replacement of the rating and comment, by the review's author. */
    @Operation(summary = "Edit a review",
            description = "By its author: the rating and comment are replaced. The previous version stays in the audit history.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "The updated review", content = @Content(mediaType = "application/json", examples = @ExampleObject(ApiExamples.REVIEW))),
                    @ApiResponse(responseCode = "403", description = "Not the author", content = @Content(mediaType = "application/problem+json", examples = @ExampleObject(ApiExamples.NOT_ALLOWED)))})
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json", examples = @ExampleObject(ApiExamples.REVIEW_REQUEST)))
    @PutMapping("/reviews/{id}")
    public ReviewView update(@PathVariable long id,
                             @RequestHeader(ApiHeaders.DEMO_USER_ID) long userId,
                             @Valid @RequestBody ReviewRequest request) {
        return reviewService.update(id, request, userId);
    }

    @Operation(summary = "Delete a review",
            description = "By its author. Its history is kept.",
            responses = {
                    @ApiResponse(responseCode = "204", description = "Deleted"),
                    @ApiResponse(responseCode = "403", description = "Not the author", content = @Content(mediaType = "application/problem+json", examples = @ExampleObject(ApiExamples.NOT_ALLOWED)))})
    @DeleteMapping("/reviews/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id, @RequestHeader(ApiHeaders.DEMO_USER_ID) long userId) {
        reviewService.delete(id, userId);
    }
}
