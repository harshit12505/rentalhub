package com.rentalhub.dto;

import java.time.Instant;

/** One review, as the API (and later the pages) show it. */
public record ReviewView(
        long id,
        long propertyId,
        Author author,
        int rating,
        String comment,
        Instant createdAt) {

    public record Author(long id, String fullName) {
    }
}
