package com.rentalhub.payment;

import com.rentalhub.domain.model.enums.Currency;
import com.rentalhub.domain.model.enums.PaymentProvider;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A stand-in for Stripe when no Stripe key is configured, so the whole booking and payment
 * flow can be tried (and shown on a deployment) without a Stripe account. Nothing is charged
 * anywhere, and every booking it handles says SIMULATED.
 *
 * It answers to Stripe's own test payment-method ids, so the same requests work unchanged
 * once a real test key is added:
 * <ul>
 *   <li>{@code pm_card_visa}, {@code pm_card_mastercard}, any other {@code pm_card_...}: succeeds;</li>
 *   <li>any {@code pm_card_...} containing "Declined" ({@code pm_card_visa_chargeDeclined},
 *       {@code pm_card_visa_chargeDeclinedInsufficientFunds}, ...): declined;</li>
 *   <li>{@code pm_card_authenticationRequired}: the bank wants 3-D Secure.</li>
 * </ul>
 * And to two of its own, for what test cards can't show:
 * <ul>
 *   <li>{@value #NO_ANSWER}: the payment succeeds, but its answer is "lost", as when the
 *       network fails at the worst moment. The booking waits for the reconciliation job.</li>
 *   <li>{@value #PROVIDER_DOWN}: the provider refuses the request. Nothing is charged.</li>
 * </ul>
 * Any other id is unknown, and declined, as Stripe would.
 *
 * Payments live in memory only. After a restart the simulator has forgotten them, and the
 * reconciliation job treats a forgotten payment as never made.
 */
@Slf4j
public class SimulatedPaymentGateway implements PaymentGateway {

    public static final String NO_ANSWER = "pm_sim_noAnswer";
    public static final String PROVIDER_DOWN = "pm_sim_providerDown";

    private static final String TEST_CARD = "pm_card_";
    private static final String DECLINED = "Declined";
    private static final String AUTHENTICATION_REQUIRED = "pm_card_authenticationRequired";

    private final Map<String, Payment> payments = new ConcurrentHashMap<>();
    /** Idempotency: the same key gets the same answer, as with Stripe. */
    private final Map<String, String> created = new ConcurrentHashMap<>();
    private final Map<String, String> refunds = new ConcurrentHashMap<>();

    /** Where a simulated payment stands. */
    public enum State { CREATED, SUCCEEDED, DECLINED, AUTHENTICATION_REQUIRED, CANCELED, REFUNDED }

    /** One simulated payment, for tests and for looking at while debugging. */
    public record Payment(String reference, long bookingId, BigDecimal amount, Currency currency, State state) {

        Payment in(State next) {
            return new Payment(reference, bookingId, amount, currency, next);
        }
    }

    @Override
    public PaymentProvider provider() {
        return PaymentProvider.SIMULATED;
    }

    @Override
    public String createPayment(PaymentRequest request) {
        return created.computeIfAbsent(request.idempotencyKey(), key -> {
            // Random, not a counter: a counter would start again after a restart, and a new
            // payment could then be mistaken for a forgotten one with the same id.
            String reference = "sim_pi_" + randomId();
            payments.put(reference, new Payment(reference, request.bookingId(), request.amount(),
                    request.currency(), State.CREATED));
            return reference;
        });
    }

    @Override
    public PaymentOutcome confirmPayment(String reference, String paymentMethodId, String idempotencyKey) {
        Payment payment = payments.get(reference);
        if (payment == null) {
            return PaymentOutcome.notCharged("no such simulated payment");
        }
        if (payment.state() == State.SUCCEEDED) {
            return PaymentOutcome.succeeded();
        }
        if (NO_ANSWER.equals(paymentMethodId)) {
            // The money is taken, but the answer never reaches the caller.
            payments.put(reference, payment.in(State.SUCCEEDED));
            return PaymentOutcome.undecided("simulated: the answer was lost");
        }
        if (PROVIDER_DOWN.equals(paymentMethodId)) {
            return PaymentOutcome.notCharged("simulated: the provider refused the request");
        }
        if (AUTHENTICATION_REQUIRED.equals(paymentMethodId)) {
            payments.put(reference, payment.in(State.AUTHENTICATION_REQUIRED));
            return PaymentOutcome.authenticationRequired("requires_action");
        }
        if (!paymentMethodId.startsWith(TEST_CARD) || paymentMethodId.contains(DECLINED)) {
            payments.put(reference, payment.in(State.DECLINED));
            return PaymentOutcome.declined(paymentMethodId.startsWith(TEST_CARD)
                    ? "card_declined" : "resource_missing: no such payment method");
        }
        payments.put(reference, payment.in(State.SUCCEEDED));
        return PaymentOutcome.succeeded();
    }

    @Override
    public PaymentOutcome lookUp(String reference) {
        Payment payment = payments.get(reference);
        if (payment == null) {
            return PaymentOutcome.notCharged("no such simulated payment (forgotten after a restart?)");
        }
        return switch (payment.state()) {
            case SUCCEEDED, REFUNDED -> PaymentOutcome.succeeded();
            case DECLINED -> PaymentOutcome.declined("card_declined");
            case AUTHENTICATION_REQUIRED -> PaymentOutcome.authenticationRequired("requires_action");
            case CREATED -> PaymentOutcome.notCharged("requires_confirmation");
            case CANCELED -> PaymentOutcome.notCharged("canceled");
        };
    }

    @Override
    public void cancelPayment(String reference) {
        Payment payment = payments.get(reference);
        if (payment == null) {
            throw new PaymentGatewayException("No simulated payment " + reference);
        }
        if (payment.state() == State.SUCCEEDED || payment.state() == State.REFUNDED) {
            throw new PaymentGatewayException("Simulated payment " + reference + " succeeded; it can only be refunded");
        }
        payments.put(reference, payment.in(State.CANCELED));
    }

    @Override
    public String refund(String reference, String idempotencyKey) {
        return refunds.computeIfAbsent(idempotencyKey, key -> {
            Payment payment = payments.get(reference);
            if (payment == null) {
                // Forgotten after a restart. No real money was ever taken, so there is none to
                // hold back: let the booking reach REFUNDED.
                log.atWarn().setMessage("payment.simulated.refundOfForgottenPayment")
                        .addKeyValue("reference", reference)
                        .log();
            } else if (payment.state() != State.SUCCEEDED) {
                throw new PaymentGatewayException("Simulated payment " + reference + " is " + payment.state()
                        + "; there is nothing to refund");
            } else {
                payments.put(reference, payment.in(State.REFUNDED));
            }
            return "sim_re_" + randomId();
        });
    }

    /** A payment as the simulator holds it: for tests, and for looking at while debugging. */
    public Optional<Payment> find(String reference) {
        return Optional.ofNullable(payments.get(reference));
    }

    private static String randomId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }
}
