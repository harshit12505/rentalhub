package com.rentalhub.config;

import com.rentalhub.domain.model.enums.PaymentProvider;
import com.rentalhub.payment.PaymentGateways;
import com.rentalhub.payment.PaymentSettings;
import com.rentalhub.payment.SimulatedPaymentGateway;
import com.rentalhub.payment.StripePaymentGateway;
import com.stripe.StripeClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Chooses, once at startup, how payments are taken.
 *
 * <ul>
 *   <li>With a Stripe test-mode secret key (STRIPE_SECRET_KEY), payments go to Stripe.</li>
 *   <li>Without one, they are simulated (see SimulatedPaymentGateway). The app still works
 *       end to end with no Stripe account: missing credentials degrade only their own
 *       feature, and never stop the app from starting.</li>
 *   <li>Any other key (a live one, sk_live_...) is refused with an error in the log, and
 *       payments are simulated. This project takes test payments only, and a key pasted into
 *       the wrong place must never charge a real card.</li>
 * </ul>
 * The simulator always exists, even with Stripe active, so that bookings it took earlier can
 * still be looked up and refunded. The key itself is never logged.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PaymentSettings.class)
public class StripeConfig {

    private static final List<String> TEST_KEY_PREFIXES = List.of("sk_test_", "rk_test_");

    @Bean
    public SimulatedPaymentGateway simulatedPaymentGateway() {
        return new SimulatedPaymentGateway();
    }

    @Bean
    public PaymentGateways paymentGateways(PaymentSettings settings, SimulatedPaymentGateway simulated) {
        return choose(settings.stripe(), simulated);
    }

    /** Static, so a unit test can try every kind of key without starting Spring. */
    static PaymentGateways choose(PaymentSettings.Stripe stripe, SimulatedPaymentGateway simulated) {
        String key = stripe.secretKey() == null ? "" : stripe.secretKey().strip();
        if (key.isEmpty()) {
            log.atInfo().setMessage("payments.mode")
                    .addKeyValue("provider", PaymentProvider.SIMULATED)
                    .addKeyValue("reason", "no STRIPE_SECRET_KEY: nothing is charged")
                    .log();
            return new PaymentGateways(simulated, List.of());
        }
        if (TEST_KEY_PREFIXES.stream().noneMatch(key::startsWith)) {
            log.atError().setMessage("payments.stripe.keyRefused")
                    .addKeyValue("reason", "STRIPE_SECRET_KEY is not a test-mode key; payments are simulated instead")
                    .log();
            return new PaymentGateways(simulated, List.of());
        }

        StripeClient.StripeClientBuilder client = StripeClient.builder()
                .setApiKey(key)
                .setConnectTimeout(Math.toIntExact(stripe.connectTimeout().toMillis()))
                .setReadTimeout(Math.toIntExact(stripe.readTimeout().toMillis()))
                .setMaxNetworkRetries(stripe.maxNetworkRetries());
        boolean customBase = stripe.apiBase() != null && !stripe.apiBase().isBlank();
        if (customBase) {
            client.setApiBase(stripe.apiBase().strip());
        }
        log.atInfo().setMessage("payments.mode")
                .addKeyValue("provider", PaymentProvider.STRIPE)
                .addKeyValue("apiBase", customBase ? stripe.apiBase().strip() : "stripe")
                .log();
        return new PaymentGateways(new StripePaymentGateway(client.build()), List.of(simulated));
    }
}
