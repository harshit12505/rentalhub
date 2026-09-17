package com.rentalhub.web.rest;

import com.rentalhub.cache.CacheNames;
import com.rentalhub.cache.TwoLevelCache;
import com.rentalhub.domain.model.enums.Currency;
import com.rentalhub.domain.repository.UserRepository;
import com.rentalhub.dto.PropertyRequest;
import com.rentalhub.dto.PropertyView;
import com.rentalhub.payment.SimulatedPaymentGateway;
import com.rentalhub.service.PropertyService;
import com.rentalhub.support.IntegrationTest;
import com.rentalhub.support.TestRequests;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prices in other currencies, through the API, at the fixed test rates (FixedExchangeRates:
 * 1 USD = 80 INR = 0.80 EUR): shown when asked for, compared fairly by maxPrice, and never
 * stored anywhere.
 */
class CurrencyApiTest extends IntegrationTest {

    private static final String HEADER = ApiHeaders.DEMO_USER_ID;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private PropertyService propertyService;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private SimulatedPaymentGateway simulator;

    private long hostId;
    private long guestId;

    @BeforeEach
    void createUsers() {
        hostId = users.save(TestRequests.host()).getId();
        guestId = users.save(TestRequests.guest()).getId();
    }

    @Test
    @DisplayName("?currency=USD adds a displayPrice beside the listing's own price, which stays as it is")
    void listingInDollars() throws Exception {
        long id = propertyService.create(TestRequests.validApartment(), hostId).id();   // ₹2,500

        mvc.perform(get("/api/properties/{id}", id).param("currency", "USD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pricePerNight").value(2500.0))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.displayPrice.amount").value(31.25))
                .andExpect(jsonPath("$.displayPrice.currency").value("USD"))
                .andExpect(jsonPath("$.displayPrice.rate").value(0.0125))
                .andExpect(jsonPath("$.displayPrice.ratesAsOf").isNotEmpty());
    }

    @Test
    @DisplayName("no currency, no displayPrice; and neither cache tier ever holds a converted price")
    void cachesHoldNoConvertedPrice() throws Exception {
        long id = propertyService.create(TestRequests.validApartment(), hostId).id();

        mvc.perform(get("/api/properties/{id}", id).param("currency", "USD"))
                .andExpect(jsonPath("$.displayPrice.amount").value(31.25));
        mvc.perform(get("/api/properties/{id}", id))
                .andExpect(jsonPath("$.displayPrice").doesNotExist());

        assertThat(redis.opsForValue().get("rentalhub:v2:propertyById::" + id))
                .contains("\"displayPrice\":null")
                .doesNotContain("31.25");
        Cache localTier = ((TwoLevelCache) Objects.requireNonNull(cacheManager.getCache(CacheNames.PROPERTY_BY_ID))).localTier();
        PropertyView cached = (PropertyView) Objects.requireNonNull(localTier.get(id)).get();
        assertThat(cached.displayPrice()).isNull();
    }

    @Test
    @DisplayName("maxPrice in dollars compares every listing in its own currency, and the page shows dollars")
    void maxPriceAcrossCurrencies() throws Exception {
        propertyService.create(TestRequests.validApartment(), hostId);                              // ₹2,500  = $31.25
        propertyService.create(TestRequests.validVilla(), hostId);                                  // ₹12,000 = $150
        propertyService.create(priced(TestRequests.validStudio(), "40.00", Currency.USD), hostId);  // $40
        propertyService.create(priced(TestRequests.validCabin(), "30.00", Currency.EUR), hostId);   // €30 = $37.50

        mvc.perform(get("/api/properties").param("maxPrice", "40").param("currency", "USD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].title", containsInAnyOrder("Test apartment", "Test studio", "Test cabin")))
                .andExpect(jsonPath("$.content[?(@.title == 'Test cabin')].displayPrice.amount").value(hasItem(37.5)))
                .andExpect(jsonPath("$.content[?(@.title == 'Test studio')].displayPrice.amount").value(hasItem(40.0)))
                .andExpect(jsonPath("$.exchangeRatesUnavailable").value(false));
        // The currency is part of the cache key: "40 dollars" and "40 rupees" are different searches.
        assertThat(redis.hasKey("rentalhub:v2:propertySearch::city:|guests:|maxPrice:40|currency:USD|page:0|size:20"))
                .isTrue();

        // Without a currency, maxPrice is in rupees, and nothing is converted for display.
        // €30 is exactly ₹3,000, and a limit includes its own boundary.
        mvc.perform(get("/api/properties").param("maxPrice", "3000"))
                .andExpect(jsonPath("$.content[*].title", containsInAnyOrder("Test apartment", "Test cabin")))
                .andExpect(jsonPath("$.content[0].displayPrice").doesNotExist());
    }

    @Test
    @DisplayName("a converted total is display only: what is stored, audited and charged stays in rupees")
    void convertedTotalIsNeverPersisted() throws Exception {
        long listingId = propertyService.create(TestRequests.validApartment(), hostId).id();   // ₹2,500 a night
        LocalDate checkIn = LocalDate.now().plusDays(30);

        MvcResult booked = mvc.perform(post("/api/bookings").param("currency", "USD").header(HEADER, guestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"propertyId": %d, "checkIn": "%s", "checkOut": "%s", "guests": 2,
                                 "paymentMethodId": "pm_card_visa"}
                                """.formatted(listingId, checkIn, checkIn.plusDays(3))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalAmount").value(7500.00))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.displayTotal.amount").value(93.75))
                .andExpect(jsonPath("$.displayTotal.currency").value("USD"))
                .andReturn();
        String location = booked.getResponse().getHeader("Location");
        long bookingId = Long.parseLong(location.substring(location.lastIndexOf('/') + 1));

        // Another reader, another currency: worked out afresh, from the same stored rupees.
        mvc.perform(get("/api/bookings/{id}", bookingId).param("currency", "EUR").header(HEADER, guestId))
                .andExpect(jsonPath("$.displayTotal.amount").value(75.00))
                .andExpect(jsonPath("$.totalAmount").value(7500.00));

        // The row, and every row of its history, hold the listing's own amount and currency.
        Map<String, Object> row = jdbc.queryForMap("SELECT total_amount, currency FROM bookings WHERE id = ?", bookingId);
        assertThat((BigDecimal) row.get("total_amount")).isEqualByComparingTo("7500.00");
        assertThat(row.get("currency")).isEqualTo("INR");
        assertThat(jdbc.queryForList(
                "SELECT DISTINCT total_amount::text || ' ' || currency FROM bookings_aud WHERE id = ?", String.class, bookingId))
                .containsExactly("7500.0000 INR");
        // And there is nowhere a converted figure could be put: no column is for one.
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND (column_name LIKE '%display%' OR column_name LIKE '%convert%' OR column_name LIKE '%exchange%')
                """, Long.class)).isZero();
        // The charge itself: in rupees, the listing's currency, whatever currency the guest looked in.
        String reference = jdbc.queryForObject("SELECT payment_reference FROM bookings WHERE id = ?", String.class, bookingId);
        SimulatedPaymentGateway.Payment charged = simulator.find(reference).orElseThrow();
        assertThat(charged.currency()).isEqualTo(Currency.INR);
        assertThat(charged.amount()).isEqualByComparingTo("7500.00");
    }

    @Test
    @DisplayName("a currency RentalHub doesn't know is a 400")
    void unknownCurrency() throws Exception {
        long id = propertyService.create(TestRequests.validApartment(), hostId).id();

        mvc.perform(get("/api/properties/{id}", id).param("currency", "XYZ")).andExpect(status().isBadRequest());
    }

    private static PropertyRequest priced(PropertyRequest request, String price, Currency currency) {
        request.setPricePerNight(new BigDecimal(price));
        request.setCurrency(currency);
        return request;
    }
}
