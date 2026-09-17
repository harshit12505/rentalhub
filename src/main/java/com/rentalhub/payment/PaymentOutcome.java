package com.rentalhub.payment;

/**
 * What a payment provider said about a payment, reduced to what RentalHub acts on.
 *
 * @param status what happened
 * @param detail the provider's own code or reason (card_declined/insufficient_funds,
 *               requires_action, ApiConnectionException, ...), for the logs
 */
public record PaymentOutcome(Status status, String detail) {

    public enum Status {
        /** The money was taken. */
        SUCCEEDED,
        /**
         * Not known yet: the provider is still processing, or its answer never arrived (a
         * timeout, a network error, an error inside the provider). The money may or may not
         * have been taken, so nothing is released; the payment reconciliation job asks again.
         */
        UNDECIDED,
        /** The card was declined. Nothing was charged. */
        DECLINED,
        /**
         * The card's bank wants the cardholder to approve the payment (3-D Secure). That needs
         * the guest's browser, which this API doesn't have. Nothing was charged.
         */
        AUTHENTICATION_REQUIRED,
        /**
         * The provider refused the request before acting on it (a bad key, the rate limit), the
         * payment was never confirmed, or the provider has no such payment. Nothing was charged.
         */
        NOT_CHARGED
    }

    public static PaymentOutcome succeeded() {
        return new PaymentOutcome(Status.SUCCEEDED, "succeeded");
    }

    public static PaymentOutcome undecided(String detail) {
        return new PaymentOutcome(Status.UNDECIDED, detail);
    }

    public static PaymentOutcome declined(String detail) {
        return new PaymentOutcome(Status.DECLINED, detail);
    }

    public static PaymentOutcome authenticationRequired(String detail) {
        return new PaymentOutcome(Status.AUTHENTICATION_REQUIRED, detail);
    }

    public static PaymentOutcome notCharged(String detail) {
        return new PaymentOutcome(Status.NOT_CHARGED, detail);
    }
}
