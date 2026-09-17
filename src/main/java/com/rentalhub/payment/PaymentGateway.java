package com.rentalhub.payment;

import com.rentalhub.domain.model.enums.PaymentProvider;

/**
 * A payment provider, as RentalHub uses one: Stripe (test mode), or the simulator used when
 * no Stripe key is configured.
 *
 * A payment is taken in two calls, create and then confirm, rather than one, so that the
 * provider's id for the payment can be saved on the booking in between. Whatever goes wrong
 * after that (a crash, a lost answer), the booking says which payment to ask about.
 *
 * Implementations make network calls. Never call them inside a database transaction.
 */
public interface PaymentGateway {

    PaymentProvider provider();

    /**
     * Creates a payment for the amount, without taking any money yet.
     *
     * @return the provider's id for the payment
     * @throws PaymentGatewayException if it could not be created; nothing was charged
     */
    String createPayment(PaymentRequest request);

    /**
     * Tries to take the money with the guest's payment method. Never throws: every way this
     * can end is one of the outcomes.
     */
    PaymentOutcome confirmPayment(String reference, String paymentMethodId, String idempotencyKey);

    /** What has happened to a payment so far. Never throws: a question left unanswered is UNDECIDED. */
    PaymentOutcome lookUp(String reference);

    /**
     * Cancels a payment that did not succeed, so that it can never be completed later.
     *
     * @throws PaymentGatewayException if the provider did not confirm the cancellation
     */
    void cancelPayment(String reference);

    /**
     * Gives back the whole amount of a successful payment.
     *
     * @return the provider's id for the refund, or null if the payment had already been
     *         refunded some other way (from the provider's dashboard, say)
     * @throws PaymentGatewayException if the refund failed or its answer never came; trying
     *         again with the same key is safe
     */
    String refund(String reference, String idempotencyKey);
}
