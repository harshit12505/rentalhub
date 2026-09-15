package com.rentalhub.web;

import com.rentalhub.audit.AuditActor;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The filter on its own, with a chain that records what it saw while the request ran. */
class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();
    private final Map<String, String> seen = new HashMap<>();
    private final FilterChain recordingChain = (request, response) -> {
        seen.put("requestId", MDC.get(RequestIdFilter.MDC_REQUEST_ID));
        seen.put("userId", MDC.get(RequestIdFilter.MDC_USER_ID));
        seen.put("actor", AuditActor.current());
    };

    @Test
    @DisplayName("a request gets a fresh id, echoed in the response and tagged on its logs with the user")
    void tagsTheRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/bookings");
        request.addHeader("X-Demo-User-Id", "7");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, recordingChain);

        String requestId = response.getHeader(RequestIdFilter.REQUEST_ID_HEADER);
        assertThat(requestId).matches("[0-9a-f]{8}");
        assertThat(seen).containsEntry("requestId", requestId)
                .containsEntry("userId", "7")
                .containsEntry("actor", "user:7");
    }

    @Test
    @DisplayName("nothing is left behind on the thread once the request is done")
    void cleansUp() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/bookings");
        request.addHeader("X-Demo-User-Id", "7");

        filter.doFilter(request, new MockHttpServletResponse(), recordingChain);

        assertThat(MDC.get(RequestIdFilter.MDC_REQUEST_ID)).isNull();
        assertThat(MDC.get(RequestIdFilter.MDC_USER_ID)).isNull();
        assertThat(AuditActor.current()).isEqualTo(AuditActor.ANONYMOUS);
    }

    @Test
    @DisplayName("a request id supplied by the caller is kept when it looks like an id")
    void keepsASafeSuppliedId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/properties");
        request.addHeader(RequestIdFilter.REQUEST_ID_HEADER, "edge-proxy.42_a");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, recordingChain);

        assertThat(response.getHeader(RequestIdFilter.REQUEST_ID_HEADER)).isEqualTo("edge-proxy.42_a");
    }

    @Test
    @DisplayName("a supplied id that could forge a log line is replaced, not repeated")
    void replacesAnUnsafeSuppliedId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/properties");
        request.addHeader(RequestIdFilter.REQUEST_ID_HEADER, "x\n2026-09-15 INFO booking.created forged=true");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, recordingChain);

        assertThat(response.getHeader(RequestIdFilter.REQUEST_ID_HEADER)).matches("[0-9a-f]{8}");
    }

    @Test
    @DisplayName("without a usable user header the request is anonymous")
    void anonymousWithoutAUser() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/properties");
        request.addHeader("X-Demo-User-Id", "not-a-number");

        filter.doFilter(request, new MockHttpServletResponse(), recordingChain);

        assertThat(seen).containsEntry("actor", AuditActor.ANONYMOUS).containsEntry("userId", null);
    }
}
