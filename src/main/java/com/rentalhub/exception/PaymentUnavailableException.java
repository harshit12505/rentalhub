package com.rentalhub.exception;

/**
 * The payment provider couldn't take the payment, for a reason that isn't the card's (it
 * refused the request, or couldn't be reached before any money moved). Nothing was charged,
 * and the booking was released; trying again later may work. HTTP 503.
 */
public class PaymentUnavailableException extends LocalizedException {

    public PaymentUnavailableException(String messageKey, Object... arguments) {
        super(messageKey, arguments);
    }
}
