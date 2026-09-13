package com.rentalhub.web.rest;

import com.rentalhub.dto.PropertyRequest;
import com.rentalhub.dto.PropertyView;
import com.rentalhub.dto.SearchCriteria;
import com.rentalhub.dto.SearchResultPage;
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

/**
 * Listings over REST.
 *
 * There is no login in this project. The acting user is named by the
 * {@value #DEMO_USER_HEADER} header, standing in for the "sign in as" switcher the web
 * pages get in phase 8. The controller only translates HTTP to service calls; every
 * rule (who may create, who may edit, what is valid) is enforced in the service.
 */
@RestController
@RequestMapping("/api/properties")
public class PropertyController {

    public static final String DEMO_USER_HEADER = "X-Demo-User-Id";

    private final PropertyService propertyService;
    private final SearchService searchService;

    public PropertyController(PropertyService propertyService, SearchService searchService) {
        this.propertyService = propertyService;
        this.searchService = searchService;
    }

    @GetMapping("/{id}")
    public PropertyView get(@PathVariable long id) {
        return propertyService.getListing(id);
    }

    @GetMapping
    public SearchResultPage search(@RequestParam(required = false) String city,
                                   @RequestParam(required = false) Integer guests,
                                   @RequestParam(required = false) BigDecimal maxPrice,
                                   @RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "" + SearchCriteria.DEFAULT_PAGE_SIZE) int size) {
        return searchService.search(new SearchCriteria(city, guests, maxPrice, page, size));
    }

    @PostMapping
    public ResponseEntity<PropertyView> create(@RequestHeader(DEMO_USER_HEADER) long userId,
                                               @Valid @RequestBody PropertyRequest request) {
        PropertyView created = propertyService.create(request, userId);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    /** Full replacement: the body is the listing's complete new state (see PropertyRequest). */
    @PutMapping("/{id}")
    public PropertyView update(@PathVariable long id,
                               @RequestHeader(DEMO_USER_HEADER) long userId,
                               @Valid @RequestBody PropertyRequest request) {
        return propertyService.update(id, request, userId);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id, @RequestHeader(DEMO_USER_HEADER) long userId) {
        propertyService.delete(id, userId);
    }
}
