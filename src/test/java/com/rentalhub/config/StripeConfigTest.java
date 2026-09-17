package com.rentalhub.config;

import com.rentalhub.domain.model.enums.PaymentProvider;
import com.rentalhub.payment.PaymentGateways;
import com.rentalhub.payment.PaymentSettings;
import com.rentalhub.payment.SimulatedPaymentGateway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** Which way payments go, for every kind of key. None of these calls reach Stripe. */
@ExtendWith(OutputCaptureExtension.class)
class StripeConfigTest {

    private final SimulatedPaymentGateway simulated = new SimulatedPaymentGateway();

    @Test
    @DisplayName("no key (unset, or empty): payments are simulated, and the app starts anyway")
    void noKey() {
        assertThat(choose(null).active().provider()).isEqualTo(PaymentProvider.SIMULATED);
        assertThat(choose("  ").active().provider()).isEqualTo(PaymentProvider.SIMULATED);
    }

    @Test
    @DisplayName("a test key: new payments go to Stripe, and the simulator stays for payments it already holds")
    void testKey() {
        PaymentGateways gateways = choose("sk_test_51Example");

        assertThat(gateways.active().provider()).isEqualTo(PaymentProvider.STRIPE);
        assertThat(gateways.forProvider(PaymentProvider.SIMULATED)).containsSame(simulated);
    }

    @Test
    @DisplayName("a live key is refused, loudly, and the key itself never reaches the log")
    void liveKeyRefused(CapturedOutput output) {
        PaymentGateways gateways = choose("sk_live_51VerySecret");

        assertThat(gateways.active().provider()).isEqualTo(PaymentProvider.SIMULATED);
        assertThat(gateways.forProvider(PaymentProvider.STRIPE)).isEmpty();
        assertThat(output).contains("payments.stripe.keyRefused").doesNotContain("51VerySecret");
    }

    private PaymentGateways choose(String secretKey) {
        return StripeConfig.choose(new PaymentSettings.Stripe(secretKey, "", Duration.ofSeconds(5),
                Duration.ofSeconds(20), 2), simulated);
    }
}
