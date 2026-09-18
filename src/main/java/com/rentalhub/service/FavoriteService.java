package com.rentalhub.service;

import com.rentalhub.domain.model.Favorite;
import com.rentalhub.domain.model.Property;
import com.rentalhub.domain.model.User;
import com.rentalhub.domain.model.enums.Currency;
import com.rentalhub.domain.repository.FavoriteRepository;
import com.rentalhub.domain.repository.PropertyRepository;
import com.rentalhub.domain.repository.UserRepository;
import com.rentalhub.dto.FavoriteView;
import com.rentalhub.exception.ResourceNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Saved listings.
 *
 * Small on purpose, but it is what the AI phase needs: a preference profile is built from what
 * a guest saves (ai/PreferenceProfileService), and until now nothing in the application could
 * save anything. The table and its "one row per user and listing" constraint have been there
 * since V1.
 *
 * Saving is <b>idempotent</b>: saving a listing you have already saved is not an error, so a
 * double click, or a client that retries after a dropped connection, changes nothing. The
 * service checks first for the everyday case, and the unique constraint settles two requests
 * that arrive at the same moment.
 */
@Slf4j
@Service
public class FavoriteService {

    private static final String ONE_PER_USER_AND_LISTING = "uq_favorite";

    private final FavoriteRepository favorites;
    private final PropertyRepository properties;
    private final UserRepository users;
    private final CurrencyService currencies;

    public FavoriteService(FavoriteRepository favorites,
                           PropertyRepository properties,
                           UserRepository users,
                           CurrencyService currencies) {
        this.favorites = favorites;
        this.properties = properties;
        this.users = users;
        this.currencies = currencies;
    }

    /**
     * Saves a listing for a user.
     *
     * @return true if it was added now, false if it was already saved
     */
    @Transactional
    public boolean save(long propertyId, long userId) {
        if (favorites.existsByUserIdAndPropertyId(userId, propertyId)) {
            return false;
        }
        Property property = properties.findById(propertyId)
                .orElseThrow(() -> new ResourceNotFoundException("property.notFound", propertyId));
        User user = users.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("user.notFound", userId));
        try {
            favorites.saveAndFlush(new Favorite(user, property));
        } catch (DataIntegrityViolationException refused) {
            if (!ConstraintViolations.violated(refused, ConstraintViolations.UNIQUE_VIOLATION, ONE_PER_USER_AND_LISTING)) {
                throw refused;
            }
            // Two requests at the same moment; the other one saved it. Same outcome either way.
            return false;
        }
        log.atInfo().setMessage("favorite.saved")
                .addKeyValue("propertyId", propertyId)
                .addKeyValue("userId", userId)
                .log();
        return true;
    }

    /** Removes a saved listing. Removing one that isn't saved changes nothing and is not an error. */
    @Transactional
    public void remove(long propertyId, long userId) {
        favorites.deleteByUserIdAndPropertyId(userId, propertyId);
        log.atDebug().setMessage("favorite.removed")
                .addKeyValue("propertyId", propertyId)
                .addKeyValue("userId", userId)
                .log();
    }

    /**
     * A user's saved listings, newest first.
     *
     * @param displayCurrency also show each price converted into this currency; null for none
     */
    @Transactional(readOnly = true)
    public List<FavoriteView> forUser(long userId, Currency displayCurrency) {
        return favorites.findWithPropertyByUserIdOrderByCreatedAtDescIdDesc(userId).stream()
                .map(favorite -> new FavoriteView(
                        currencies.inCurrency(PropertyViews.toSummary(favorite.getProperty(), null), displayCurrency),
                        favorite.getCreatedAt()))
                .toList();
    }
}
