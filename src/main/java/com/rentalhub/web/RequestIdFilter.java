package com.rentalhub.web;

import com.rentalhub.audit.AuditActor;
import com.rentalhub.web.rest.ApiHeaders;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * Gives every request an id, tags everything logged while handling it with that id and
 * the acting user, and tells the audit trail who is acting.
 *
 * <ul>
 *   <li><b>requestId</b>: the caller's {@value #REQUEST_ID_HEADER} header when it looks
 *       like an id (a proxy in front of the app may already have assigned one), else a
 *       new random one. It is echoed in the response, so someone reporting a problem can
 *       quote it and the matching log lines can be found.</li>
 *   <li><b>userId</b>: the X-Demo-User-Id header, when it holds a number; on the web pages,
 *       the user picked in the "sign in as" list (see DemoSession).</li>
 * </ul>
 * Both go into the logging MDC (a per-thread map that every log line on the thread can
 * include), so all of a request's log lines carry them; in the render profile's JSON
 * they are fields. The user also becomes the {@link AuditActor} for Envers.
 *
 * It runs before every other filter, and it clears what it set in a finally block,
 * because servlet threads are pooled: a leftover value would be stamped on some other
 * user's request.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String MDC_REQUEST_ID = "requestId";
    public static final String MDC_USER_ID = "userId";

    /**
     * What a supplied request id may look like. Anything else is replaced: the value is
     * written into log lines and a response header, and a caller must not be able to
     * smuggle a line break into either (log forging, header injection).
     */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = requestIdFor(request);
        Long userId = actingUser(request);

        response.setHeader(REQUEST_ID_HEADER, requestId);
        MDC.put(MDC_REQUEST_ID, requestId);
        if (userId != null) {
            MDC.put(MDC_USER_ID, userId.toString());
        }
        String actor = userId == null ? AuditActor.ANONYMOUS : AuditActor.user(userId);
        try (AuditActor.Scope ignored = AuditActor.as(actor)) {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_REQUEST_ID);
            MDC.remove(MDC_USER_ID);
        }
    }

    static String requestIdFor(HttpServletRequest request) {
        String supplied = request.getHeader(REQUEST_ID_HEADER);
        return supplied != null && SAFE_ID.matcher(supplied).matches() ? supplied : newRequestId();
    }

    /**
     * Eight hex characters: short enough to read in a log line, and random enough that
     * two requests close together in time practically never share one.
     */
    static String newRequestId() {
        return String.format(Locale.ROOT, "%08x", ThreadLocalRandom.current().nextInt());
    }

    /**
     * The user named by the demo header (the APIs), else the one signed in on the web pages
     * (the session), else null. A header that isn't a number is not an acting user.
     */
    private static Long actingUser(HttpServletRequest request) {
        String header = request.getHeader(ApiHeaders.DEMO_USER_ID);
        if (header == null) {
            return DemoSession.userId(request);
        }
        try {
            return Long.parseLong(header.trim());
        } catch (NumberFormatException notAnId) {
            return null;   // the controller answers 400 for it; nothing to attribute here
        }
    }
}
