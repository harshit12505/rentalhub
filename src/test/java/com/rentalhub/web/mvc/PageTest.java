package com.rentalhub.web.mvc;

import com.rentalhub.support.IntegrationTest;
import jakarta.servlet.http.Cookie;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What every web-page test needs: sign in the way the navbar does, read a page's HTML, and
 * follow a redirect the way a browser would.
 *
 * It adds no annotations, so its tests share IntegrationTest's one application context. And,
 * like every API test, they are not @Transactional: each form really commits, as it does for a
 * browser, so what the next page shows is what the database holds.
 */
abstract class PageTest extends IntegrationTest {

    @Autowired
    protected MockMvc mvc;

    /** Signs in through the "sign in as" form itself, and returns the session a browser would keep. */
    protected MockHttpSession signIn(long userId) throws Exception {
        MvcResult result = mvc.perform(post("/session/user").param("userId", String.valueOf(userId)))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    /**
     * The HTML of a page that rendered normally. Thymeleaf prints a message key it cannot find as
     * ??key_locale?? rather than failing, so every page read here is also checked for that.
     */
    protected static String html(ResultActions page) throws Exception {
        return html(page, 200);
    }

    protected static String html(ResultActions page, int expectedStatus) throws Exception {
        String html = page.andExpect(status().is(expectedStatus))
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).as("a message key missing from the page").doesNotContain("??");
        return html;
    }

    /**
     * Follows a redirect as a browser would: the same session, the cookies the response set (the
     * chosen language), and the flash attributes it carried (a notice, or a refused form).
     */
    protected ResultActions follow(MvcResult redirect) throws Exception {
        String target = redirect.getResponse().getRedirectedUrl();
        assertThat(target).as("a redirect").isNotNull();
        MockHttpServletRequestBuilder next = get(target).flashAttrs(redirect.getFlashMap());
        MockHttpSession session = (MockHttpSession) redirect.getRequest().getSession(false);
        if (session != null && !session.isInvalid()) {
            next.session(session);
        }
        Cookie[] cookies = redirect.getResponse().getCookies();
        if (cookies.length > 0) {
            next.cookie(cookies);
        }
        return mvc.perform(next);
    }

    /** A form post from a signed-in browser. */
    protected static MockHttpServletRequestBuilder form(String url, MockHttpSession session, Object... uriVariables) {
        MockHttpServletRequestBuilder form = post(url, uriVariables).contentType(MediaType.APPLICATION_FORM_URLENCODED);
        return session == null ? form : form.session(session);
    }
}
