package com.rentalhub.payment;

import com.rentalhub.domain.model.enums.Currency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The stand-in for Stripe: the same test ids, the same shape of answers, no money anywhere. */
class SimulatedPaymentGatewayTest {

    private final SimulatedPaymentGateway simulator = new SimulatedPaymentGateway();

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "pm_card_visa, SUCCEEDED",
            "pm_card_mastercard, SUCCEEDED",
            "pm_card_visa_chargeDeclined, DECLINED",
            "pm_card_visa_chargeDeclinedInsufficientFunds, DECLINED",
            "pm_card_authenticationRequired, AUTHENTICATION_REQUIRED",
            "pm_sim_noAnswer, UNDECIDED",
            "pm_sim_providerDown, NOT_CHARGED",
            "pm_nothing_like_this, DECLINED"})
    @DisplayName("answers to Stripe's test payment-method ids, and two of its own")
    void answersLikeStripe(String paymentMethod, PaymentOutcome.Status expected) {
        String reference = create("key-" + paymentMethod);

        assertThat(simulator.confirmPayment(reference, paymentMethod, "confirm").status()).isEqualTo(expected);
    }

    @Test
    @DisplayName("the same idempotency key gets the same payment back, as with Stripe")
    void idempotentCreate() {
        String first = create("same-key");

        assertThat(create("same-key")).isEqualTo(first).startsWith("sim_pi_");
        assertThat(create("other-key")).isNotEqualTo(first);
    }

    @Test
    @DisplayName("a lost answer: the confirm call says 'undecided', but the payment went through")
    void noAnswerStillCharges() {
        String reference = create("k");

        simulator.confirmPayment(reference, SimulatedPaymentGateway.NO_ANSWER, "c");

        assertThat(simulator.lookUp(reference).status()).isEqualTo(PaymentOutcome.Status.SUCCEEDED);
    }

    @Test
    @DisplayName("a payment never confirmed, or cancelled, was never charged")
    void notConfirmedNotCharged() {
        String reference = create("k");
        assertThat(simulator.lookUp(reference).status()).isEqualTo(PaymentOutcome.Status.NOT_CHARGED);

        simulator.cancelPayment(reference);
        assertThat(simulator.find(reference).orElseThrow().state()).isEqualTo(SimulatedPaymentGateway.State.CANCELED);
        assertThat(simulator.lookUp("sim_pi_forgotten").status()).isEqualTo(PaymentOutcome.Status.NOT_CHARGED);
    }

    @Test
    @DisplayName("only a successful payment can be refunded, and the same key never refunds twice")
    void refunds() {
        String reference = create("k");
        assertThatThrownBy(() -> simulator.refund(reference, "r")).isInstanceOf(PaymentGatewayException.class);

        simulator.confirmPayment(reference, "pm_card_visa", "c");
        String refund = simulator.refund(reference, "r");

        assertThat(refund).startsWith("sim_re_");
        assertThat(simulator.refund(reference, "r")).isEqualTo(refund);
        assertThat(simulator.find(reference).orElseThrow().state()).isEqualTo(SimulatedPaymentGateway.State.REFUNDED);
        assertThatThrownBy(() -> simulator.cancelPayment(reference)).isInstanceOf(PaymentGatewayException.class);
    }

    private String create(String key) {
        return simulator.createPayment(new PaymentRequest(1L, new BigDecimal("7500.00"), Currency.INR, key));
    }
}
