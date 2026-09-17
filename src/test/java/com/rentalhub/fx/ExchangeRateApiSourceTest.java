package com.rentalhub.fx;

import com.rentalhub.domain.model.enums.Currency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The live-rates client against canned answers. MockRestServiceServer stands in for the
 * network: the RestClient sends its request to it, and it answers with what the test says.
 */
class ExchangeRateApiSourceTest {

    private static final URI URL = URI.create("https://open.er-api.com/v6/latest/USD");

    /** Trimmed from a real answer (15 Sep 2026), including currencies RentalHub doesn't use. */
    private static final String LATEST = """
            {"result":"success","provider":"https://www.exchangerate-api.com",
             "time_last_update_unix":1789430551,"time_last_update_utc":"Tue, 15 Sep 2026 00:02:31 +0000",
             "base_code":"USD",
             "rates":{"USD":1,"AED":3.6725,"EUR":0.865688,"GBP":0.740963,"INR":95.674534,"JPY":154.389032}}
            """;

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final ExchangeRateApiSource source = new ExchangeRateApiSource(builder.build(), URL);

    @Test
    @DisplayName("reads RentalHub's currencies, digit for digit, and when the provider updated them")
    void readsTheRates() {
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(LATEST, MediaType.APPLICATION_JSON));

        ExchangeRates rates = source.fetchLatest();

        assertThat(rates.perBaseUnit()).containsOnlyKeys(Currency.values());
        // Exactly as written: read from the JSON's digits, never through a double.
        assertThat(rates.perBaseUnit().get(Currency.INR).toPlainString()).isEqualTo("95.674534");
        assertThat(rates.perBaseUnit().get(Currency.GBP).toPlainString()).isEqualTo("0.740963");
        assertThat(rates.asOf()).isEqualTo(Instant.parse("2026-09-15T00:02:31Z"));
        server.verify();
    }

    @Test
    @DisplayName("an error answer is not a set of rates")
    void errorAnswer() {
        server.expect(requestTo(URL))
                .andRespond(withSuccess("{\"result\":\"error\",\"error-type\":\"unsupported-code\"}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(source::fetchLatest)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unsupported-code");
    }

    @Test
    @DisplayName("the provider's rate limit (HTTP 429) surfaces as an exception, for the caller to ride out")
    void rateLimited() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(source::fetchLatest).isInstanceOf(RestClientException.class);
    }

    @Test
    @DisplayName("an answer with none of RentalHub's currencies is refused")
    void noUsefulRates() {
        server.expect(requestTo(URL)).andRespond(withSuccess(
                "{\"result\":\"success\",\"time_last_update_unix\":1789430551,\"rates\":{\"JPY\":154.389032}}",
                MediaType.APPLICATION_JSON));

        assertThatThrownBy(source::fetchLatest).isInstanceOf(IllegalStateException.class);
    }
}
