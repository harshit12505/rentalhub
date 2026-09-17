package com.rentalhub.payment;

import com.rentalhub.domain.model.enums.Currency;

import java.math.BigDecimal;

/**
 * A payment to create: one booking's total, in the listing's own currency.
 *
 * @param idempotencyKey the same key for the same step of the same booking, so that a repeated
 *                       request (a retry after a lost answer) gets the first answer back
 *                       instead of creating a second payment
 */
public record PaymentRequest(long bookingId, BigDecimal amount, Currency currency, String idempotencyKey) {
}
