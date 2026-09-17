package com.rentalhub.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Payment settings, bound from {@code rentalhub.payments.*} in application.yml.
 */
@ConfigurationProperties("rentalhub.payments")
public record PaymentSettings(@DefaultValue Stripe stripe) {

    /**
     * @param secretKey         a Stripe test-mode secret key (sk_test_... or rk_test_...), from
     *                          the STRIPE_SECRET_KEY environment variable. Empty: payments are
     *                          simulated
     * @param apiBase           where Stripe's API is. Empty means Stripe itself; set it only to
     *                          point at stripe-mock, Stripe's offline mock server
     * @param connectTimeout    how long to wait to connect to Stripe
     * @param readTimeout       how long to wait for Stripe's answer. Generous: a card payment
     *                          can take several seconds
     * @param maxNetworkRetries how often the Stripe library itself repeats a request whose
     *                          answer was lost. Safe, because every request carries an
     *                          idempotency key
     */
    public record Stripe(
            String secretKey,
            String apiBase,
            @DefaultValue("5s") Duration connectTimeout,
            @DefaultValue("20s") Duration readTimeout,
            @DefaultValue("2") int maxNetworkRetries) {
    }
}
