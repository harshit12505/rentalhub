package com.rentalhub.web.graphql;

import com.jayway.jsonpath.JsonPath;
import com.rentalhub.domain.repository.UserRepository;
import com.rentalhub.dto.PropertyRequest;
import com.rentalhub.service.PropertyService;
import com.rentalhub.support.IntegrationTest;
import com.rentalhub.support.TestRequests;
import com.rentalhub.web.rest.ApiHeaders;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultHandler;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The GraphQL API, over HTTP, exactly as a client sends it: a POST to /graphql with a query and
 * its variables. Errors come back with status 200 and an {@code errors} list, which is how
 * GraphQL works; what is checked is each error's classification, translated message and
 * messageKey.
 */
class GraphQlApiTest extends IntegrationTest {

    private static final String SEARCH = """
            query Search($filter: SearchFilter) {
              searchProperties(filter: $filter, size: 50) {
                totalElements
                content { id title typeLabel pricePerNight host { fullName } images { url } }
              }
            }
            """;

    private static final String DETAIL = """
            query Detail($id: ID!) {
              property(id: $id) {
                id title typeLabel pricePerNight currency
                host { id fullName }
                attributes { name label value valueLabel }
                reviews { rating }
              }
            }
            """;

    private static final String BOOK = """
            mutation Book($input: BookingInput!) {
              createBooking(input: $input) {
                id status nights totalAmount currency
                payment { status provider }
              }
            }
            """;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JsonMapper json;

    @Autowired
    private UserRepository users;

    @Autowired
    private PropertyService properties;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private long hostId;
    private long guestId;

    @BeforeEach
    void createUsers() {
        hostId = users.save(TestRequests.host()).getId();
        guestId = users.save(TestRequests.guest()).getId();
    }

    @Test
    @DisplayName("a listing's detail: translated labels, generic attributes, and money as an exact string")
    void detail() throws Exception {
        long id = properties.create(TestRequests.validVilla(), hostId).id();

        graphql(DETAIL, Map.of("id", id), null, "hi")
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.property.typeLabel").value("विला"))
                .andExpect(jsonPath("$.data.property.pricePerNight").value("12000.0000"))
                .andExpect(jsonPath("$.data.property.host.fullName").value("Asha Menon"))
                .andExpect(jsonPath("$.data.property.attributes[0].name").value("plotAreaSqm"))
                .andExpect(jsonPath("$.data.property.attributes[0].label").value("प्लॉट का क्षेत्रफल (m²)"))
                .andExpect(jsonPath("$.data.property.attributes[0].value").value("450.00"))
                .andExpect(jsonPath("$.data.property.attributes[1].name").value("hasPool"))
                .andExpect(jsonPath("$.data.property.attributes[1].valueLabel").value("हाँ"))
                .andExpect(jsonPath("$.data.property.reviews", hasSize(0)));

        graphql(DETAIL, Map.of("id", 999), null, null)
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.property").doesNotExist());
    }

    @Test
    @DisplayName("hosts and photos for a whole page cost the same few queries for 2 listings as for 8")
    void batchLoadingAvoidsNPlusOne() throws Exception {
        createListings(2);
        long queriesForTwo = statementsFor(() -> graphql(SEARCH, Map.of(), null, null)
                .andExpect(jsonPath("$.data.searchProperties.content", hasSize(2)))
                .andExpect(jsonPath("$.data.searchProperties.content[0].host.fullName").value("Asha Menon")));

        createListings(6);
        long queriesForEight = statementsFor(() -> graphql(SEARCH, Map.of(), null, null)
                .andExpect(jsonPath("$.data.searchProperties.content", hasSize(8)))
                .andExpect(jsonPath("$.data.searchProperties.content[7].host.fullName").value("Asha Menon")));

        assertThat(queriesForEight)
                .as("one query for all the hosts and one for all the photos, however many listings")
                .isEqualTo(queriesForTwo)
                .isLessThanOrEqualTo(6);
    }

    @Test
    @DisplayName("booking over GraphQL is the same saga as REST: paid, confirmed, with the total as a string")
    void bookAndCancel() throws Exception {
        long listingId = properties.create(TestRequests.validVilla(), hostId).id();
        LocalDate checkIn = LocalDate.now().plusDays(30);

        MvcResult booked = graphql(BOOK, Map.of("input", Map.of(
                        "propertyId", listingId, "checkIn", checkIn.toString(),
                        "checkOut", checkIn.plusDays(3).toString(), "guests", 2,
                        "paymentMethodId", TestRequests.PAYS)), guestId, null)
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.createBooking.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.createBooking.nights").value(3))
                .andExpect(jsonPath("$.data.createBooking.totalAmount").value("36000.00"))
                .andExpect(jsonPath("$.data.createBooking.payment.status").value("PAID"))
                .andExpect(jsonPath("$.data.createBooking.payment.provider").value("SIMULATED"))
                .andReturn();
        String bookingId = JsonPath.read(booked.getResponse().getContentAsString(),
                "$.data.createBooking.id");

        graphql("mutation { cancelBooking(id: " + bookingId + ") { status payment { status } } }", Map.of(), guestId, null)
                .andExpect(jsonPath("$.data.cancelBooking.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.cancelBooking.payment.status").value("REFUNDED"));
    }

    @Test
    @DisplayName("errors carry a classification, a translated message and the same messageKey as REST")
    void translatedErrors() throws Exception {
        long listingId = properties.create(TestRequests.validVilla(), hostId).id();
        Map<String, Object> input = new LinkedHashMap<>(Map.of(
                "propertyId", listingId, "checkIn", LocalDate.now().plusDays(30).toString(),
                "checkOut", LocalDate.now().plusDays(32).toString(), "guests", 2,
                "paymentMethodId", "pm_card_visa_chargeDeclined"));

        graphql(BOOK, Map.of("input", input), null, "es")
                .andExpect(jsonPath("$.errors[0].extensions.classification").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.errors[0].extensions.messageKey").value("error.userRequired"))
                .andExpect(jsonPath("$.errors[0].message")
                        .value("Indica quién realiza la acción: envía la cabecera X-Demo-User-Id."));

        graphql(BOOK, Map.of("input", input), guestId, "hi")
                .andExpect(jsonPath("$.errors[0].extensions.classification").value("PAYMENT_FAILED"))
                .andExpect(jsonPath("$.errors[0].extensions.messageKey").value("payment.declined"))
                .andExpect(jsonPath("$.errors[0].message")
                        .value("कार्ड अस्वीकार हो गया, और कोई राशि नहीं काटी गई। कृपया कोई दूसरा कार्ड आज़माएँ।"));

        graphql("mutation { createReview(propertyId: " + listingId + ", input: {rating: 5}) { id } }",
                Map.of(), guestId, null)
                .andExpect(jsonPath("$.errors[0].extensions.classification").value("FORBIDDEN"))
                .andExpect(jsonPath("$.errors[0].extensions.messageKey").value("review.notStayed"));
    }

    @Test
    @DisplayName("bean validation runs on GraphQL input too, with each broken field named, in the caller's language")
    void validation() throws Exception {
        long listingId = properties.create(TestRequests.validVilla(), hostId).id();

        graphql("mutation { createReview(propertyId: " + listingId + ", input: {rating: 9}) { id } }",
                Map.of(), guestId, "es")
                .andExpect(jsonPath("$.errors[0].extensions.classification").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.errors[0].message").value("Algunos campos no son válidos."))
                .andExpect(jsonPath("$.errors[0].extensions.errors[0].field").value("rating"))
                .andExpect(jsonPath("$.errors[0].extensions.errors[0].message")
                        .value("La valoración debe ser un número entero del 1 al 5."));
    }

    private void createListings(int count) {
        for (int i = 0; i < count; i++) {
            PropertyRequest villa = TestRequests.validVilla();
            villa.setTitle("Villa " + i);
            properties.create(villa, hostId);
        }
        // So the search really runs, instead of being answered from the cache.
        cacheManager.getCacheNames().forEach(name -> Objects.requireNonNull(cacheManager.getCache(name)).invalidate());
    }

    private long statementsFor(ThrowingRunnable request) throws Exception {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        request.run();
        return statistics.getPrepareStatementCount();
    }

    private ResultActions graphql(String document, Map<String, Object> variables, Long userId, String language)
            throws Exception {
        MockHttpServletRequestBuilder request = post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("query", document, "variables", variables)));
        if (userId != null) {
            request.header(ApiHeaders.DEMO_USER_ID, userId);
        }
        if (language != null) {
            request.header(HttpHeaders.ACCEPT_LANGUAGE, language);
        }
        MvcResult result = mvc.perform(request).andReturn();
        if (result.getRequest().isAsyncStarted()) {
            // Spring for GraphQL may answer asynchronously; then the answer is picked up here.
            return mvc.perform(asyncDispatch(result)).andExpect(status().isOk());
        }
        return completed(result).andExpect(status().isOk());
    }

    /** The finished request, as ResultActions, so both paths above are asserted the same way. */
    private static ResultActions completed(MvcResult result) {
        return new ResultActions() {
            @Override
            public ResultActions andExpect(ResultMatcher matcher) throws Exception {
                matcher.match(result);
                return this;
            }

            @Override
            public ResultActions andDo(ResultHandler handler) throws Exception {
                handler.handle(result);
                return this;
            }

            @Override
            public MvcResult andReturn() {
                return result;
            }
        };
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
