package com.rentalhub.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * A guest's review of a listing, for creating one (POST) or replacing it (PUT).
 *
 * A mutable class rather than a record because the review form (phase 8) binds to it.
 */
@Getter
@Setter
public class ReviewRequest {

    @NotNull(message = "{validation.required}")
    @Min(value = 1, message = "{review.rating.range}")
    @Max(value = 5, message = "{review.rating.range}")
    private Integer rating;

    /** Optional. Blank is stored as no comment. */
    @Size(max = 2000, message = "{validation.maxLength}")
    private String comment;
}
