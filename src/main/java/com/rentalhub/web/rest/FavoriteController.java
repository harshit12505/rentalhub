package com.rentalhub.web.rest;

import com.rentalhub.domain.model.enums.Currency;
import com.rentalhub.dto.FavoriteView;
import com.rentalhub.service.FavoriteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Saved listings, for the acting user ({@value ApiHeaders#DEMO_USER_ID}).
 *
 * Saving is a PUT rather than a POST because it says "let this listing be saved", which is
 * true however many times you send it. A PUT that runs twice leaves the same one favourite,
 * and a client that retries a dropped request needs no special handling.
 */
@Tag(name = "Favourites", description = "Listings a user has saved. The AI preference profile and the like-my-favourites search are built from these.")
@RestController
@RequestMapping("/api")
public class FavoriteController {

    private final FavoriteService favorites;

    public FavoriteController(FavoriteService favorites) {
        this.favorites = favorites;
    }

    @Operation(summary = "Save a listing",
            description = "Idempotent: saving twice leaves one favourite.",
            responses = {
                    @ApiResponse(responseCode = "204", description = "Saved"),
                    @ApiResponse(responseCode = "404", description = "There is no such listing", content = @Content(mediaType = "application/problem+json", examples = @ExampleObject(ApiExamples.NOT_FOUND)))})
    @PutMapping("/properties/{propertyId}/favorite")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void save(@PathVariable long propertyId, @RequestHeader(ApiHeaders.DEMO_USER_ID) long userId) {
        favorites.save(propertyId, userId);
    }

    @Operation(summary = "Unsave a listing",
            description = "Removing one that is not saved is not an error.",
            responses = {
                    @ApiResponse(responseCode = "204", description = "Removed"),
                    @ApiResponse(responseCode = "400", description = "The X-Demo-User-Id header is missing", content = @Content(mediaType = "application/problem+json", examples = @ExampleObject(ApiExamples.INVALID_FIELDS)))})
    @DeleteMapping("/properties/{propertyId}/favorite")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable long propertyId, @RequestHeader(ApiHeaders.DEMO_USER_ID) long userId) {
        favorites.remove(propertyId, userId);
    }

    /** @param currency optional: also show each price converted into this currency */
    @Operation(summary = "My saved listings",
            description = "Newest first. Add ?currency= to also see each price converted.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "The saved listings", content = @Content(mediaType = "application/json", examples = @ExampleObject(ApiExamples.FAVORITES)))})
    @GetMapping("/favorites")
    public List<FavoriteView> mine(@RequestHeader(ApiHeaders.DEMO_USER_ID) long userId,
                                   @RequestParam(required = false) Currency currency) {
        return favorites.forUser(userId, currency);
    }
}
