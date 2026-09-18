package com.rentalhub.dto;

import java.time.Instant;

/**
 * One saved listing: the card a favourites page shows, and when it was saved.
 *
 * Favourites are what the AI preference profile is built from (see ai/PreferenceProfileService),
 * which is why the whole card is here rather than just an id.
 */
public record FavoriteView(PropertySummary listing, Instant savedAt) {

    /** A copy carrying a different view of the listing, such as one with a converted price. */
    public FavoriteView withListing(PropertySummary listing) {
        return new FavoriteView(listing, savedAt);
    }
}
