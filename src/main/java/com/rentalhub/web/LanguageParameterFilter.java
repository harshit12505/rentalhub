package com.rentalhub.web;

import com.rentalhub.config.WebConfig;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.util.Locale;

/**
 * Applies {@code ?lang=hi} to the request it is on, and remembers it (see config/WebConfig).
 *
 * <b>Why a filter and not Spring's LocaleChangeInterceptor.</b> An interceptor only runs once
 * a controller has been chosen, so on the errors that happen before that — a wrong method
 * (405), a path that does not exist (404) — {@code ?lang=} was silently ignored. A filter sees
 * every request.
 *
 * On a multipart upload it reads the language from the query string only: {@code getParameter}
 * would make the servlet container read the whole upload here, before the application's size
 * limits and error handling get a say.
 */
@Component
public class LanguageParameterFilter extends OncePerRequestFilter {

    private final LocaleResolver localeResolver;

    public LanguageParameterFilter(LocaleResolver localeResolver) {
        this.localeResolver = localeResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String language = languageOf(request);
        if (language != null && language.strip().matches("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*")) {
            // The resolver ignores a language the app does not speak (?lang=fr).
            localeResolver.setLocale(request, response, Locale.forLanguageTag(language.strip()));
        }
        chain.doFilter(request, response);
    }

    /**
     * The requested language, if any. For an upload, straight from the query string; for
     * everything else, as an ordinary request parameter (which also covers a form field).
     */
    private static String languageOf(HttpServletRequest request) {
        String contentType = request.getContentType();
        if (contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("multipart/")) {
            String query = request.getQueryString();
            return query == null ? null
                    : UriComponentsBuilder.newInstance().query(query).build()
                            .getQueryParams().getFirst(WebConfig.LANGUAGE_PARAMETER);
        }
        return request.getParameter(WebConfig.LANGUAGE_PARAMETER);
    }
}
