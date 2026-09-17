package com.rentalhub.web;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.rentalhub.domain.repository.UserRepository;
import com.rentalhub.service.BookingService;
import com.rentalhub.service.PropertyService;
import com.rentalhub.support.IntegrationTest;
import com.rentalhub.support.TestRequests;
import com.rentalhub.web.rest.ApiHeaders;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Structured logging, end to end: a booking made through the API is logged as an event
 * name with its fields as separate key/value pairs, tagged with the request's id and
 * user (MDC), and printed locally as "booking.created bookingId=... ".
 */
@ExtendWith(OutputCaptureExtension.class)
class StructuredLoggingTest extends IntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private PropertyService propertyService;

    private final Logger bookingLog = (Logger) LoggerFactory.getLogger(BookingService.class);
    private final ListAppender<ILoggingEvent> events = new ListAppender<>();

    @BeforeEach
    void listen() {
        events.start();
        bookingLog.addAppender(events);
    }

    @AfterEach
    void stopListening() {
        bookingLog.detachAppender(events);
    }

    @Test
    @DisplayName("a booking is logged with its fields as key/value pairs, tagged with the request id and user")
    void bookingCreatedIsStructured(CapturedOutput output) throws Exception {
        long hostId = users.save(TestRequests.host()).getId();
        long guestId = users.save(TestRequests.guest()).getId();
        long listingId = propertyService.create(TestRequests.validApartment(), hostId).id();
        LocalDate checkIn = LocalDate.now().plusDays(30);

        mvc.perform(post("/api/bookings").header(ApiHeaders.DEMO_USER_ID, guestId)
                        .header(RequestIdFilter.REQUEST_ID_HEADER, "test-req-42")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"propertyId": %d, "checkIn": "%s", "checkOut": "%s", "guests": 2,
                                 "paymentMethodId": "pm_card_visa"}
                                """.formatted(listingId, checkIn, checkIn.plusDays(3))))
                .andExpect(status().isCreated())
                .andExpect(header().string(RequestIdFilter.REQUEST_ID_HEADER, "test-req-42"));

        ILoggingEvent created = events.list.stream()
                .filter(event -> event.getMessage().equals("booking.created"))
                .findFirst().orElseThrow();
        Map<String, Object> fields = created.getKeyValuePairs().stream()
                .collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));
        assertThat(fields).containsEntry("propertyId", listingId)
                .containsEntry("guestId", guestId)
                .containsEntry("checkIn", checkIn)
                .containsKeys("bookingId", "total", "currency");
        assertThat(created.getKeyValuePairs()).extracting((KeyValuePair pair) -> pair.key)
                .startsWith("bookingId", "propertyId", "guestId");
        assertThat(created.getMDCPropertyMap())
                .containsEntry("requestId", "test-req-42")
                .containsEntry("userId", String.valueOf(guestId));

        // How the local console pattern (%kvp{NONE}) prints it.
        assertThat(output).contains("test-req-42")
                .containsPattern("booking\\.created bookingId=\\d+ propertyId=" + listingId + " guestId=" + guestId);
    }
}
