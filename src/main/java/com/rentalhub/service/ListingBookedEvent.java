package com.rentalhub.service;

/**
 * "A booking was made at this listing." Published inside the booking's transaction and
 * acted on only after it commits (see PropertyCacheInvalidator).
 *
 * Booking a listing raises its version number (see PropertyRepository.findForBookingById),
 * and the cached listing view shows that version, so the cached copy has to go. Nothing
 * else about the listing changed, so its search pages stay cached.
 */
public record ListingBookedEvent(long propertyId) {
}
