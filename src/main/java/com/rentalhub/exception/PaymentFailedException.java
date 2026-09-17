package com.rentalhub.exception;

/**
 * The guest's payment method didn't work: the card was declined, or its bank wants a check
 * this API can't do yet. Nothing was charged, and the booking was released. HTTP 402.
 */
public class PaymentFailedException extends LocalizedException {

    public PaymentFailedException(String messageKey, Object... arguments) {
        super(messageKey, arguments);
    }
}
