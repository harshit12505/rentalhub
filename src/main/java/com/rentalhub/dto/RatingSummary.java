package com.rentalhub.dto;

import java.math.BigDecimal;

/**
 * How a listing is rated, as a card shows it.
 *
 * @param average the average rating, to one decimal place
 * @param reviews how many reviews it is the average of
 */
public record RatingSummary(BigDecimal average, long reviews) {
}
