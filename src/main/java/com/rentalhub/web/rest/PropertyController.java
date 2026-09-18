package com.rentalhub.web.rest;

import com.rentalhub.domain.model.enums.Currency;
import com.rentalhub.dto.ListingHistoryEntry;
import com.rentalhub.dto.PropertyRequest;
import com.rentalhub.dto.PropertyView;
import com.rentalhub.dto.SearchCriteria;
import com.rentalhub.dto.SearchResultPage;
import com.rentalhub.service.CurrencyService;
import com.rentalhub.service.ListingHistoryService;
import com.rentalhub.service.PropertyService;
import com.rentalhub.service.SearchService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.math.BigDecimal;
import java.net.URI;
import java.util.List;

/**
 * Listings over REST.
 *
 * There is no login in this project. The acting user is named by the
 * {@value ApiHeaders#DEMO_USER_ID} header, standing in for the "sign in as" switcher the
 * web pages get in phase 8. The controller only translates HTTP to service calls; every
 * rule (who may create, who may edit, what is valid) is enforced in the service.
 *
 * Reads take an optional {@code currency} (INR, USD, EUR, GBP, AED): prices are then also
 * shown converted into it, as {@code displayPrice}. The listing's own price and currency
 * are always there too, and are what a booking charges.
 */
@Tag(name = "Listings", description = "Listings of every type: apartments, villas, cabins and studios. Reads are open to anyone; changes are for hosts.")
@RestController
@RequestMapping("/api/properties")
public class PropertyController {

    private final PropertyService propertyService;
    private final SearchService searchService;
    private final ListingHistoryService historyService;
    private final CurrencyService currencyService;

    public PropertyController(PropertyService propertyService,
                              SearchService searchService,
                              ListingHistoryService historyService,
                              CurrencyService currencyService) {
        this.propertyService = propertyService;
        this.searchService = searchService;
        this.historyService = historyService;
        this.currencyService = currencyService;
    }

    @Operation(summary = "One listing",
            description = "Everything a detail page shows. Cached: first in memory, then in Redis, then from the database. Add ?currency=USD to also get the price converted (displayPrice); the real price and currency are always there too.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "The listing", content = @Content(mediaType = "application/json", examples = @ExampleObject(ApiExamples.LISTING))),
                    @ApiResponse(responseCode = "404", description = "There is no such listing", content = @Content(mediaType = "application/problem+json", examples = @ExampleObject(ApiExamples.NOT_FOUND)))})
    @GetMapping("/{id}")
    public PropertyView get(@PathVariable long id, @RequestParam(required = false) Currency currency) {
        return currencyService.inCurrency(propertyService.getListing(id), currency);
    }

    /**
     * @param maxPrice at most this price per night, in {@code currency}. Listings priced in
     *                 other currencies are compared at today's exchange rate
     * @param currency the currency to read maxPrice in and to show prices in. Without it,
     *                 maxPrice is read in the default currency (INR) and no converted prices
     *                 are added
     */
    @Operation(summary = "Search listings",
            description = "One page of active listings, newest first. Every filter is optional. maxPrice is read in currency (INR if not given) and compared with each listing in its own currency at today's rate. Pages are cached in Redis.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "One page of results", content = @Content(mediaType = "application/json", examples = @ExampleObject(ApiExamples.SEARCH_PAGE))),
                    @ApiResponse(responseCode = "400", description = "A parameter is not valid, such as an unknown currency", content = @Content(mediaType = "application/problem+json", examples = @ExampleObject(ApiExamples.BROKEN_RULE)))})
    @GetMapping
    public SearchResultPage search(@RequestParam(required = false) String city,
                                   @RequestParam(required = false) Integer guests,
                                   @RequestParam(required = false) BigDecimal maxPrice,
                                   @RequestParam(required = false) Currency currency,
                                   @RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "" + SearchCriteria.DEFAULT_PAGE_SIZE) int size) {
        SearchCriteria criteria = new SearchCriteria(city, guests, maxPrice, currencyService.orDefault(currency),
                page, size);
        return currencyService.inCurrency(searchService.search(criteria), currency);
    }

    @Operation(summary = "Create a listing",
            description = "As a host (X-Demo-User-Id). The type decides which extra attributes are required, and each type has rules of its own (a villa needs a plot of at least 100 m2).",
            responses = {
                    @ApiResponse(responseCode = "201", description = "Created; the Location header points at it", content = @Content(mediaType = "application/json", examples = @ExampleObject(ApiExamples.LISTING))),
                    @ApiResponse(responseCode = "400", description = "A field is missing or a type rule is broken", content = @Content(mediaType = "application/problem+json", examples = @ExampleObject(ApiExamples.INVALID_FIELDS))),
                    @ApiResponse(responseCode = "403", description = "The acting user is not a host", content = @Content(mediaType = "application/problem+json", examples = @ExampleObject(ApiExamples.NOT_ALLOWED)))})
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json", examples = @ExampleObject(ApiExamples.VILLA_REQUEST)))
    @PostMapping
    public ResponseEntity<PropertyView> create(@RequestHeader(ApiHeaders.DEMO_USER_ID) long userId,
                                               @Valid @RequestBody PropertyRequest request) {
        PropertyView created = propertyService.create(request, userId);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    /** Full replacement: the body is the listing's complete new state (see PropertyRequest). */
    @Operation(summary = "Replace a listing",
            description = "By its host. Full replacement: the body is the listing's complete new state. The type cannot change.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "The updated listing", content = @Content(mediaType = "application/json", examples = @ExampleObject(ApiExamples.LISTING))),
                    @ApiResponse(responseCode = "400", description = "A field or a type rule is broken", content = @Content(mediaType = "application/problem+json", examples = @ExampleObject(ApiExamples.BROKEN_RULE))),
                    @ApiResponse(responseCode = "403", description = "Not the host of this listing", content = @Content(mediaType = "application/problem+json", examples = @ExampleObject(ApiExamples.NOT_ALLOWED))),
                    @ApiResponse(responseCode = "404", description = "There is no such listing", content = @Content(mediaType = "application/problem+json", examples = @ExampleObject(ApiExamples.NOT_FOUND)))})
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "application/json", examples = @ExampleObject(ApiExamples.VILLA_REQUEST)))
    @PutMapping("/{id}")
    public PropertyView update(@PathVariable long id,
                               @RequestHeader(ApiHeaders.DEMO_USER_ID) long userId,
                               @Valid @RequestBody PropertyRequest request) {
        return propertyService.update(id, request, userId);
    }

    @Operation(summary = "Delete a listing",
            description = "By its host. A listing with bookings cannot be deleted (409): bookings are payment records. Its photos are deleted from storage too, after the delete commits.",
            responses = {
                    @ApiResponse(responseCode = "204", description = "Deleted"),
                    @ApiResponse(responseCode = "403", description = "Not the host of this listing", content = @Content(mediaType = "application/problem+json", examples = @ExampleObject(ApiExamples.NOT_ALLOWED))),
                    @ApiResponse(responseCode = "404", description = "There is no such listing", content = @Content(mediaType = "application/problem+json", examples = @ExampleObject(ApiExamples.NOT_FOUND)))})
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id, @RequestHeader(ApiHeaders.DEMO_USER_ID) long userId) {
        propertyService.delete(id, userId);
    }

    /**
     * Every recorded change to the listing, oldest first: when, by whom, and what changed.
     * Only its host may see it, and still can after deleting the listing.
     */
    @Operation(summary = "A listing's change history",
            description = "For its host: every recorded change, oldest first, with who made it and exactly which fields changed. Still readable after the listing is deleted.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "The history", content = @Content(mediaType = "application/json", examples = @ExampleObject(ApiExamples.HISTORY))),
                    @ApiResponse(responseCode = "403", description = "Not the host of this listing", content = @Content(mediaType = "application/problem+json", examples = @ExampleObject(ApiExamples.NOT_ALLOWED)))})
    @GetMapping("/{id}/history")
    public List<ListingHistoryEntry> history(@PathVariable long id,
                                             @RequestHeader(ApiHeaders.DEMO_USER_ID) long userId) {
        return historyService.history(id, userId);
    }
}
