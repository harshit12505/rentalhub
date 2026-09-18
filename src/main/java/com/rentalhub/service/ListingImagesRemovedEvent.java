package com.rentalhub.service;

import java.util.List;

/**
 * "These photos' rows are gone; their files can go too." Published inside the transaction that
 * removed the rows, and acted on only after it commits (see ImageObjectCleaner).
 *
 * That order is the point. Deleting the file first and then failing to commit would leave a
 * listing pointing at a photo that no longer exists. The other way round, the worst case is a
 * file nobody points at, which costs a few kilobytes and is logged.
 *
 * @param keys the storage keys of the removed photos
 */
public record ListingImagesRemovedEvent(List<String> keys) {

    public ListingImagesRemovedEvent {
        keys = List.copyOf(keys);
    }
}
