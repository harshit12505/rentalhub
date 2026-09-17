package com.rentalhub.payment;

import com.rentalhub.domain.model.enums.Currency;
import com.stripe.StripeClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Stripe gateway against a fake Stripe: a tiny HTTP server, started by the test, that
 * answers the way Stripe's API does. The real Stripe library talks to it (its address is set
 * with setApiBase), so this proves what is actually sent (amounts in paise, the idempotency
 * key, redirects switched off) and how every kind of answer is understood, with no Stripe
 * account and no network.
 */
class StripePaymentGatewayTest {

    private static final String KEY = "rentalhub-booking-42-1789430551123-create";

    private static final String REQUIRES_PAYMENT_METHOD = pi("requires_payment_method");
    private static final String SUCCEEDED = pi("succeeded");

    private final List<Recorded> requests = new CopyOnWriteArrayList<>();
    private final ExecutorService handlers = Executors.newCachedThreadPool();
    private volatile Responder responder = () -> new Reply(200, REQUIRES_PAYMENT_METHOD);
    private HttpServer fakeStripe;
    private StripePaymentGateway gateway;

    @BeforeEach
    void startFakeStripe() throws IOException {
        fakeStripe = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        fakeStripe.setExecutor(handlers);
        fakeStripe.createContext("/", exchange -> {
            try (exchange) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                requests.add(new Recorded(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                        URLDecoder.decode(body, StandardCharsets.UTF_8),
                        exchange.getRequestHeaders().getFirst("Idempotency-Key")));
                Reply reply = responder.reply();
                byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(reply.status(), bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (Exception clientGaveUp) {
                // The no-answer test: the client stopped waiting before this reply was written.
            }
        });
        fakeStripe.start();

        gateway = new StripePaymentGateway(StripeClient.builder()
                .setApiKey("sk_test_not_a_real_key")
                .setApiBase("http://127.0.0.1:" + fakeStripe.getAddress().getPort())
                .setMaxNetworkRetries(0)
                .setReadTimeout(1000)
                .build());
    }

    @AfterEach
    void stopFakeStripe() {
        fakeStripe.stop(0);
        handlers.shutdownNow();
    }

    // --------------------------------------------------------------- create

    @Test
    @DisplayName("creating a payment sends the amount in paise, the booking id, no redirects, and the idempotency key")
    void createPayment() {
        String reference = gateway.createPayment(new PaymentRequest(42L, new BigDecimal("7500.00"), Currency.INR, KEY));

        assertThat(reference).isEqualTo("pi_123");
        Recorded sent = requests.getFirst();
        assertThat(sent.method()).isEqualTo("POST");
        assertThat(sent.path()).isEqualTo("/v1/payment_intents");
        assertThat(sent.body()).contains(
                "amount=750000",
                "currency=inr",
                "metadata[booking_id]=42",
                "automatic_payment_methods[enabled]=true",
                "automatic_payment_methods[allow_redirects]=never");
        assertThat(sent.idempotencyKey()).isEqualTo(KEY);
    }

    @Test
    @DisplayName("a payment that can't even be created is an exception: no money can have moved")
    void createFails() {
        answer(500, error("api_error", null, null, null));

        assertThatThrownBy(() -> gateway.createPayment(new PaymentRequest(42L, BigDecimal.TEN, Currency.INR, KEY)))
                .isInstanceOf(PaymentGatewayException.class);
    }

    // -------------------------------------------------------------- confirm

    @Test
    @DisplayName("a confirmed payment has succeeded; the card and the idempotency key are sent")
    void confirmSucceeds() {
        answer(200, SUCCEEDED);

        assertThat(confirm().status()).isEqualTo(PaymentOutcome.Status.SUCCEEDED);
        Recorded sent = requests.getFirst();
        assertThat(sent.path()).isEqualTo("/v1/payment_intents/pi_123/confirm");
        assertThat(sent.body()).contains("payment_method=pm_card_visa");
        assertThat(sent.idempotencyKey()).isEqualTo("key-confirm");
    }

    @Test
    @DisplayName("a declined card (HTTP 402) is DECLINED, with Stripe's reason kept for the logs")
    void declined() {
        answer(402, error("card_error", "card_declined", "insufficient_funds", null));

        PaymentOutcome outcome = confirm();

        assertThat(outcome.status()).isEqualTo(PaymentOutcome.Status.DECLINED);
        assertThat(outcome.detail()).contains("card_declined/insufficient_funds");
    }

    @Test
    @DisplayName("a card whose bank wants 3-D Secure needs a browser this API doesn't have")
    void authenticationRequired() {
        answer(200, pi("requires_action"));

        assertThat(confirm().status()).isEqualTo(PaymentOutcome.Status.AUTHENTICATION_REQUIRED);
    }

    @Test
    @DisplayName("a payment method Stripe doesn't know is the guest's to change, like a declined card")
    void unknownPaymentMethod() {
        answer(400, error("invalid_request_error", "resource_missing", null, "payment_method"));

        assertThat(confirm().status()).isEqualTo(PaymentOutcome.Status.DECLINED);
    }

    @Test
    @DisplayName("a bad key (HTTP 401) is refused before anything happens: NOT_CHARGED")
    void badKey() {
        answer(401, error("invalid_request_error", null, null, null));

        assertThat(confirm().status()).isEqualTo(PaymentOutcome.Status.NOT_CHARGED);
    }

    @Test
    @DisplayName("an error inside Stripe (HTTP 500) may or may not have charged the card: UNDECIDED")
    void stripeError() {
        answer(500, error("api_error", null, null, null));

        assertThat(confirm().status()).isEqualTo(PaymentOutcome.Status.UNDECIDED);
    }

    @Test
    @DisplayName("no answer at all (a timeout) is UNDECIDED too: only asking again later can tell")
    void noAnswer() {
        responder = () -> {
            Thread.sleep(3000);
            return new Reply(200, SUCCEEDED);
        };

        assertThat(confirm().status()).isEqualTo(PaymentOutcome.Status.UNDECIDED);
    }

    // --------------------------------------------------------------- look up

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "succeeded, SUCCEEDED",
            "processing, UNDECIDED",
            "requires_action, AUTHENTICATION_REQUIRED",
            "requires_confirmation, NOT_CHARGED",
            "requires_payment_method, NOT_CHARGED",
            "canceled, NOT_CHARGED"})
    @DisplayName("looking a payment up reads its status")
    void lookUp(String stripeStatus, PaymentOutcome.Status expected) {
        answer(200, pi(stripeStatus));

        assertThat(gateway.lookUp("pi_123").status()).isEqualTo(expected);
        assertThat(requests.getFirst().method()).isEqualTo("GET");
    }

    @Test
    @DisplayName("a payment that was declined earlier is looked up as DECLINED, from its last error")
    void lookUpDeclined() {
        answer(200, """
                {"id":"pi_123","object":"payment_intent","status":"requires_payment_method",
                 "last_payment_error":{"type":"card_error","code":"card_declined","decline_code":"generic_decline"}}
                """);

        PaymentOutcome outcome = gateway.lookUp("pi_123");

        assertThat(outcome.status()).isEqualTo(PaymentOutcome.Status.DECLINED);
        assertThat(outcome.detail()).isEqualTo("card_declined/generic_decline");
    }

    @Test
    @DisplayName("a payment Stripe has never heard of was never charged")
    void lookUpMissing() {
        answer(404, error("invalid_request_error", "resource_missing", null, "intent"));

        assertThat(gateway.lookUp("pi_nope").status()).isEqualTo(PaymentOutcome.Status.NOT_CHARGED);
    }

    // ---------------------------------------------------------------- refund

    @Test
    @DisplayName("a refund is created for the whole payment, with its own idempotency key")
    void refund() {
        answer(200, "{\"id\":\"re_123\",\"object\":\"refund\",\"status\":\"succeeded\",\"payment_intent\":\"pi_123\"}");

        assertThat(gateway.refund("pi_123", "key-refund")).isEqualTo("re_123");
        Recorded sent = requests.getFirst();
        assertThat(sent.path()).isEqualTo("/v1/refunds");
        assertThat(sent.body()).contains("payment_intent=pi_123").doesNotContain("amount");
        assertThat(sent.idempotencyKey()).isEqualTo("key-refund");
    }

    @Test
    @DisplayName("a payment already refunded some other way needs no second refund")
    void alreadyRefunded() {
        answer(400, error("invalid_request_error", "charge_already_refunded", null, null));

        assertThat(gateway.refund("pi_123", "key-refund")).isNull();
    }

    @Test
    @DisplayName("a refund that fails is an exception, so the booking stays 'refund owed'")
    void refundFails() {
        answer(500, error("api_error", null, null, null));

        assertThatThrownBy(() -> gateway.refund("pi_123", "key-refund")).isInstanceOf(PaymentGatewayException.class);
    }

    // --------------------------------------------------------------- helpers

    private PaymentOutcome confirm() {
        return gateway.confirmPayment("pi_123", "pm_card_visa", "key-confirm");
    }

    private void answer(int status, String json) {
        responder = () -> new Reply(status, json);
    }

    private static String pi(String status) {
        return "{\"id\":\"pi_123\",\"object\":\"payment_intent\",\"status\":\"" + status
                + "\",\"amount\":750000,\"currency\":\"inr\"}";
    }

    /** A Stripe error body; null fields are left out. */
    private static String error(String type, String code, String declineCode, String param) {
        StringBuilder json = new StringBuilder("{\"error\":{\"type\":\"").append(type).append("\",\"message\":\"test\"");
        if (code != null) {
            json.append(",\"code\":\"").append(code).append('"');
        }
        if (declineCode != null) {
            json.append(",\"decline_code\":\"").append(declineCode).append('"');
        }
        if (param != null) {
            json.append(",\"param\":\"").append(param).append('"');
        }
        return json.append("}}").toString();
    }

    private record Recorded(String method, String path, String body, String idempotencyKey) {
    }

    private record Reply(int status, String body) {
    }

    @FunctionalInterface
    private interface Responder {
        Reply reply() throws Exception;
    }
}
