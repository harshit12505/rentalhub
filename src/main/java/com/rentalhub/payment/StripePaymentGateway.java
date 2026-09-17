package com.rentalhub.payment;

import com.rentalhub.domain.model.enums.PaymentProvider;
import com.stripe.StripeClient;
import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.ApiException;
import com.stripe.exception.CardException;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.StripeError;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentConfirmParams;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.RefundCreateParams;

import java.util.Locale;

/**
 * Payments through Stripe's PaymentIntents API, in test mode.
 *
 * A PaymentIntent is Stripe's record of one attempt to collect one amount. It is created (no
 * money moves), confirmed with a payment method, and then ends up succeeded, declined (back to
 * requires_payment_method), waiting for the cardholder's bank (requires_action), processing,
 * or canceled.
 *
 * The payment is confirmed here, on the server, with a PaymentMethod id sent by the client:
 * in test mode, one of Stripe's ready-made test cards such as {@code pm_card_visa}. Card
 * numbers never reach RentalHub. Payment methods that redirect the payer to their bank's site
 * are switched off ({@code allow_redirects=never}), because there is no browser to redirect.
 *
 * Every call that changes something carries an idempotency key. If the Stripe library repeats
 * a request whose answer was lost, Stripe replays its first answer instead of acting twice.
 */
public class StripePaymentGateway implements PaymentGateway {

    /** The metadata key that links a PaymentIntent (and its dashboard page) back to the booking. */
    static final String BOOKING_ID = "booking_id";

    private static final String PAYMENT_METHOD_PARAM = "payment_method";
    private static final String RESOURCE_MISSING = "resource_missing";
    private static final String ALREADY_REFUNDED = "charge_already_refunded";

    private final StripeClient stripe;

    public StripePaymentGateway(StripeClient stripe) {
        this.stripe = stripe;
    }

    @Override
    public PaymentProvider provider() {
        return PaymentProvider.STRIPE;
    }

    @Override
    public String createPayment(PaymentRequest request) {
        PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                // Stripe takes amounts in the currency's smallest unit: ₹7,500.00 is 750000 paise.
                .setAmount(request.currency().toMinorUnits(request.amount()))
                .setCurrency(request.currency().name().toLowerCase(Locale.ROOT))
                .setDescription("RentalHub booking " + request.bookingId())
                .putMetadata(BOOKING_ID, String.valueOf(request.bookingId()))
                .setAutomaticPaymentMethods(PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                        .setEnabled(true)
                        .setAllowRedirects(PaymentIntentCreateParams.AutomaticPaymentMethods.AllowRedirects.NEVER)
                        .build())
                .build();
        try {
            return stripe.v1().paymentIntents().create(params, idempotent(request.idempotencyKey())).getId();
        } catch (StripeException e) {
            // Whatever went wrong, a PaymentIntent that was never confirmed has taken no money.
            throw new PaymentGatewayException("Stripe did not create the payment: " + describe(e), e);
        }
    }

    @Override
    public PaymentOutcome confirmPayment(String reference, String paymentMethodId, String idempotencyKey) {
        PaymentIntentConfirmParams params = PaymentIntentConfirmParams.builder()
                .setPaymentMethod(paymentMethodId)
                .build();
        try {
            return outcomeOf(stripe.v1().paymentIntents().confirm(reference, params, idempotent(idempotencyKey)));
        } catch (CardException e) {
            // A declined card: Stripe answers HTTP 402, with the reason.
            return PaymentOutcome.declined(describe(e));
        } catch (InvalidRequestException e) {
            // A payment method Stripe doesn't know, or can't use here, is the guest's to change,
            // just like a declined card. Any other refusal means our request was wrong.
            return PAYMENT_METHOD_PARAM.equals(e.getParam())
                    ? PaymentOutcome.declined(describe(e))
                    : PaymentOutcome.notCharged(describe(e));
        } catch (ApiConnectionException | ApiException e) {
            // No answer, or Stripe failed while handling the request: the card may or may not
            // have been charged. Only asking again later can tell.
            return PaymentOutcome.undecided(describe(e));
        } catch (StripeException e) {
            // Refused before anything happened: a bad key, a missing permission, the rate
            // limit, an idempotency key used with different parameters.
            return PaymentOutcome.notCharged(describe(e));
        }
    }

    @Override
    public PaymentOutcome lookUp(String reference) {
        try {
            return outcomeOf(stripe.v1().paymentIntents().retrieve(reference));
        } catch (InvalidRequestException e) {
            return RESOURCE_MISSING.equals(e.getCode())
                    ? PaymentOutcome.notCharged(describe(e))
                    : PaymentOutcome.undecided(describe(e));
        } catch (StripeException e) {
            return PaymentOutcome.undecided(describe(e));
        }
    }

    @Override
    public void cancelPayment(String reference) {
        try {
            stripe.v1().paymentIntents().cancel(reference);
        } catch (StripeException e) {
            throw new PaymentGatewayException("Stripe did not cancel " + reference + ": " + describe(e), e);
        }
    }

    @Override
    public String refund(String reference, String idempotencyKey) {
        RefundCreateParams params = RefundCreateParams.builder()
                .setPaymentIntent(reference)
                .build();
        try {
            return stripe.v1().refunds().create(params, idempotent(idempotencyKey)).getId();
        } catch (InvalidRequestException e) {
            if (ALREADY_REFUNDED.equals(e.getCode())) {
                return null;
            }
            throw new PaymentGatewayException("Stripe did not refund " + reference + ": " + describe(e), e);
        } catch (StripeException e) {
            throw new PaymentGatewayException("Stripe did not refund " + reference + ": " + describe(e), e);
        }
    }

    /** A PaymentIntent's status, as RentalHub acts on it. */
    static PaymentOutcome outcomeOf(PaymentIntent intent) {
        String status = intent.getStatus() == null ? "" : intent.getStatus();
        return switch (status) {
            case "succeeded" -> PaymentOutcome.succeeded();
            case "requires_action" -> PaymentOutcome.authenticationRequired(status);
            // After a decline, a PaymentIntent goes back to waiting for a payment method and
            // keeps the reason; without a reason it was never tried at all.
            case "requires_payment_method" -> intent.getLastPaymentError() == null
                    ? PaymentOutcome.notCharged(status)
                    : PaymentOutcome.declined(describe(intent.getLastPaymentError()));
            // Created but never confirmed, or cancelled: no money was taken.
            case "requires_confirmation", "canceled" -> PaymentOutcome.notCharged(status);
            // "processing"; also "requires_capture", which only manual capture produces (not
            // used here), and any status newer than this code: wait and ask again.
            default -> PaymentOutcome.undecided(status);
        };
    }

    private static RequestOptions idempotent(String idempotencyKey) {
        return RequestOptions.builder().setIdempotencyKey(idempotencyKey).build();
    }

    /** "CardException card_declined/insufficient_funds requestId=req_..." for the logs. */
    private static String describe(StripeException e) {
        StringBuilder text = new StringBuilder(e.getClass().getSimpleName());
        if (e.getCode() != null) {
            text.append(' ').append(e.getCode());
        }
        if (e instanceof CardException card && card.getDeclineCode() != null) {
            text.append('/').append(card.getDeclineCode());
        }
        if (e.getRequestId() != null) {
            text.append(" requestId=").append(e.getRequestId());
        }
        return text.toString();
    }

    private static String describe(StripeError error) {
        return error.getDeclineCode() == null ? error.getCode() : error.getCode() + "/" + error.getDeclineCode();
    }
}
