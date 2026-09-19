package com.rentalhub.web.mvc;

import com.rentalhub.config.WebConfig;
import com.rentalhub.service.DemoUserService;
import com.rentalhub.web.DemoSession;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpMethod;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What every page's frame (layout.html) needs besides the page itself: who is signed in, who
 * can be, this page's own address, and the same page in the other languages.
 *
 * Added to the model of every page controller, and limited to this package, so the REST and
 * GraphQL controllers never pay for it. The error pages add it themselves (PageExceptionHandler,
 * PageErrorViewResolver): an exception handler, and Spring Boot's own error controller, never
 * run a controller advice's model methods.
 */
@Slf4j
@ControllerAdvice(basePackageClasses = PageModelAdvice.class)
public class PageModelAdvice {

    /**
     * The languages offered, each named in its own language: someone who reads only Hindi
     * should find "हिन्दी" in the list. That is also why these names are not in the message
     * files — they are the same in every language.
     */
    private static final List<Language> LANGUAGES = List.of(
            new Language("en", "English"),
            new Language("hi", "हिन्दी"),
            new Language("es", "Español"));

    private final DemoUserService demoUsers;

    public PageModelAdvice(DemoUserService demoUsers) {
        this.demoUsers = demoUsers;
    }

    @ModelAttribute
    public void addLayout(HttpServletRequest request, Model model) {
        model.addAllAttributes(layout(request));
    }

    /**
     * The layout's model attributes:
     * <ul>
     *   <li>{@code currentUser}: the signed-in demo user, or none. A session naming a user who
     *       no longer exists counts as nobody;</li>
     *   <li>{@code demoUsers}: everyone "sign in as" offers;</li>
     *   <li>{@code currentPath}: where "sign in as" comes back to;</li>
     *   <li>{@code languageLinks}: this page in each language.</li>
     * </ul>
     */
    public Map<String, Object> layout(HttpServletRequest request) {
        Map<String, Object> layout = new HashMap<>();
        Long userId = DemoSession.userId(request);
        if (userId != null) {
            demoUsers.find(userId).ifPresent(user -> layout.put("currentUser", user));
        }
        layout.put("demoUsers", demoUsers.all());
        layout.putAll(addresses(request));
        return layout;
    }

    /**
     * The same for an error page, which must still show when the error is that the database is
     * down: then without the user menu.
     */
    public Map<String, Object> layoutForErrorPage(HttpServletRequest request) {
        try {
            return layout(request);
        } catch (RuntimeException unavailable) {
            log.atWarn().setMessage("page.layoutUnavailable")
                    .addKeyValue("error", unavailable.getClass().getName()).log();
            return addresses(request);
        }
    }

    private static Map<String, Object> addresses(HttpServletRequest request) {
        String path = pagePath(request);
        String active = LocaleContextHolder.getLocale().getLanguage();
        List<LanguageLink> links = LANGUAGES.stream()
                .map(language -> new LanguageLink(language.code(), language.name(),
                        UriComponentsBuilder.fromUriString(path)
                                .replaceQueryParam(WebConfig.LANGUAGE_PARAMETER, language.code())
                                .build().toUriString(),
                        language.code().equals(active)))
                .toList();
        return Map.of("currentPath", path, "languageLinks", links);
    }

    /**
     * The address of the page being shown, within the application (no context path: the
     * templates' {@code @{...}} links and "redirect:" both add it). Every form posts and then
     * redirects, so a page is always the answer to a GET — except an error page that answers a
     * failed POST, which gets the home page instead, as a link to a POST-only address would be a
     * 405. On Spring Boot's error page the address that failed is in the request's error
     * attributes; the request itself is for /error.
     */
    static String pagePath(HttpServletRequest request) {
        boolean errorDispatch = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI) != null;
        String method = errorDispatch ? (String) request.getAttribute(RequestDispatcher.ERROR_METHOD) : request.getMethod();
        if (method != null && !HttpMethod.GET.matches(method)) {
            return "/";
        }
        String uri = errorDispatch ? (String) request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI) : request.getRequestURI();
        String query = errorDispatch ? (String) request.getAttribute(RequestDispatcher.ERROR_QUERY_STRING) : request.getQueryString();
        String path = uri.substring(request.getContextPath().length());
        return (path.isEmpty() ? "/" : path) + (query == null ? "" : "?" + query);
    }

    record Language(String code, String name) {
    }

    /** One entry of the navbar's language menu. */
    public record LanguageLink(String code, String name, String url, boolean active) {
    }
}
