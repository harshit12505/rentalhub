package com.rentalhub.payment;

import com.rentalhub.domain.model.enums.PaymentProvider;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The payment providers this instance can reach, and which of them takes new payments.
 *
 * An existing payment is looked up or refunded through the provider that took it, which is
 * not always the one taking new payments: a Stripe key may have been added since. Built once,
 * at startup, by StripeConfig.
 */
public final class PaymentGateways {

    private final PaymentGateway active;
    private final Map<PaymentProvider, PaymentGateway> byProvider;

    /**
     * @param active the provider new payments go to
     * @param others providers kept only for payments they already hold
     */
    public PaymentGateways(PaymentGateway active, List<PaymentGateway> others) {
        this.active = active;
        EnumMap<PaymentProvider, PaymentGateway> all = new EnumMap<>(PaymentProvider.class);
        others.forEach(gateway -> all.put(gateway.provider(), gateway));
        all.put(active.provider(), active);
        this.byProvider = Collections.unmodifiableMap(all);
    }

    /** The provider new payments go to. */
    public PaymentGateway active() {
        return active;
    }

    /** The provider holding an existing payment, if this instance can reach it. */
    public Optional<PaymentGateway> forProvider(PaymentProvider provider) {
        return provider == null ? Optional.empty() : Optional.ofNullable(byProvider.get(provider));
    }
}
