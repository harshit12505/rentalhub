package com.rentalhub.web.mvc;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorViewResolver;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.ModelAndView;

import java.util.HashMap;
import java.util.Map;

/**
 * Spring Boot's own error page — for an address no page or API answers (/no-such-page), or an
 * error before any page controller ran — drawn with the site's frame: the navbar, the language
 * menu and "sign in as", like every other page.
 *
 * Boot would render the same error.html without this, but with only its own model (status,
 * path, timestamp), so the navbar would come up empty. The API's own paths never get here:
 * ApiRoutingErrorHandler answers those in JSON.
 */
@Component
class PageErrorViewResolver implements ErrorViewResolver {

    private final PageModelAdvice layout;

    PageErrorViewResolver(PageModelAdvice layout) {
        this.layout = layout;
    }

    @Override
    public ModelAndView resolveErrorView(HttpServletRequest request, HttpStatus status, Map<String, Object> model) {
        Map<String, Object> page = new HashMap<>(model);
        page.putAll(layout.layoutForErrorPage(request));
        page.put("status", status.value());
        // Boot's own message is for developers ("No static resource no-such-page.") and in English;
        // without one, the page says in the reader's language that the page does not exist.
        page.remove("message");
        return new ModelAndView("error", page, status);
    }
}
