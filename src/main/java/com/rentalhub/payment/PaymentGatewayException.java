package com.rentalhub.payment;

/**
 * A call to the payment provider that did not succeed: it failed, or its answer never came.
 *
 * What that means depends on the call, which is why the caller decides what to do: nothing
 * moves when creating or cancelling a payment fails, and a refund can be tried again with the
 * same idempotency key without refunding twice.
 */
public class PaymentGatewayException extends RuntimeException {

    public PaymentGatewayException(String message) {
        super(message);
    }

    public PaymentGatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}
