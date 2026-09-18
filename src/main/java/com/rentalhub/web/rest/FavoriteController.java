package com.rentalhub.web.rest;

import com.rentalhub.domain.model.enums.Currency;
import com.rentalhub.dto.FavoriteView;
import com.rentalhub.service.FavoriteService;
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
@RestController
@RequestMapping("/api")
public class FavoriteController {

    private final FavoriteService favorites;

    public FavoriteController(FavoriteService favorites) {
        this.favorites = favorites;
    }

    @PutMapping("/properties/{propertyId}/favorite")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void save(@PathVariable long propertyId, @RequestHeader(ApiHeaders.DEMO_USER_ID) long userId) {
        favorites.save(propertyId, userId);
    }

    @DeleteMapping("/properties/{propertyId}/favorite")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable long propertyId, @RequestHeader(ApiHeaders.DEMO_USER_ID) long userId) {
        favorites.remove(propertyId, userId);
    }

    /** @param currency optional: also show each price converted into this currency */
    @GetMapping("/favorites")
    public List<FavoriteView> mine(@RequestHeader(ApiHeaders.DEMO_USER_ID) long userId,
                                   @RequestParam(required = false) Currency currency) {
        return favorites.forUser(userId, currency);
    }
}
