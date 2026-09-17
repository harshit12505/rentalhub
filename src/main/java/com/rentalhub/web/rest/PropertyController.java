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

    @PostMapping
    public ResponseEntity<PropertyView> create(@RequestHeader(ApiHeaders.DEMO_USER_ID) long userId,
                                               @Valid @RequestBody PropertyRequest request) {
        PropertyView created = propertyService.create(request, userId);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    /** Full replacement: the body is the listing's complete new state (see PropertyRequest). */
    @PutMapping("/{id}")
    public PropertyView update(@PathVariable long id,
                               @RequestHeader(ApiHeaders.DEMO_USER_ID) long userId,
                               @Valid @RequestBody PropertyRequest request) {
        return propertyService.update(id, request, userId);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id, @RequestHeader(ApiHeaders.DEMO_USER_ID) long userId) {
        propertyService.delete(id, userId);
    }

    /**
     * Every recorded change to the listing, oldest first: when, by whom, and what changed.
     * Only its host may see it, and still can after deleting the listing.
     */
    @GetMapping("/{id}/history")
    public List<ListingHistoryEntry> history(@PathVariable long id,
                                             @RequestHeader(ApiHeaders.DEMO_USER_ID) long userId) {
        return historyService.history(id, userId);
    }
}
