package com.rentalhub.domain.model.enums;

/**
 * Who holds a booking's money. Stored with the booking, because a refund, or a question
 * about what happened to a payment, must go back to the provider that took it.
 */
public enum PaymentProvider {
    /** Stripe, in test mode. */
    STRIPE,
    /** RentalHub's stand-in when no Stripe key is configured. No money moves anywhere. */
    SIMULATED
}
