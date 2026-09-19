package com.rentalhub.web.mvc;

import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The address "sign in as" and the language menu come back to. Spring Boot's error page (for an
 * address nothing answers) cannot be reached through MockMvc, which never forwards to /error, so
 * its case is checked here directly, with the attributes the servlet container sets.
 */
class PageModelAdviceTest {

    @Test
    @DisplayName("a page answers a GET: its own address, query string included, without the context path")
    void ordinaryPage() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/app/listings/5");
        request.setContextPath("/app");
        request.setQueryString("currency=USD");

        assertThat(PageModelAdvice.pagePath(request)).isEqualTo("/listings/5?currency=USD");
    }

    @Test
    @DisplayName("an error page answering a POST links home: its own address answers only POST")
    void afterAPost() {
        assertThat(PageModelAdvice.pagePath(new MockHttpServletRequest("POST", "/listings/5/book"))).isEqualTo("/");
    }

    @Test
    @DisplayName("on Spring Boot's error page, the address that failed, not /error")
    void bootErrorPage() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/no-such-page");
        request.setAttribute(RequestDispatcher.ERROR_QUERY_STRING, "lang=hi");
        request.setAttribute(RequestDispatcher.ERROR_METHOD, "GET");

        assertThat(PageModelAdvice.pagePath(request)).isEqualTo("/no-such-page?lang=hi");

        request.setAttribute(RequestDispatcher.ERROR_METHOD, "DELETE");
        assertThat(PageModelAdvice.pagePath(request)).isEqualTo("/");
    }
}
