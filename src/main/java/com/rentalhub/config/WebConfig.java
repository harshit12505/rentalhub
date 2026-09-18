package com.rentalhub.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.i18n.LocaleContext;
import org.springframework.context.i18n.TimeZoneAwareLocaleContext;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.CookieLocaleResolver;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Which language each request is answered in.
 *
 * <ol>
 *   <li>{@code ?lang=hi} on any request wins (web/LanguageParameterFilter), and is remembered
 *       in a cookie, so the web pages of phase 8 keep the language a visitor picked;</li>
 *   <li>otherwise that cookie, if there is one;</li>
 *   <li>otherwise the best match in the browser's (or client's) {@code Accept-Language};</li>
 *   <li>otherwise English.</li>
 * </ol>
 * Only the three languages the app is translated into are ever chosen. Anything else — French,
 * a malformed header, a stale cookie — becomes English, rather than a locale for which the
 * message files have nothing, which would still print English but format numbers the French way.
 *
 * Every message is resolved through this: errors (GlobalExceptionHandler), bean validation,
 * and the AI answers, all via Spring's LocaleContextHolder, which the DispatcherServlet fills
 * from this resolver on every request.
 */
@Configuration(proxyBeanMethods = false)
public class WebConfig {

    public static final Locale HINDI = Locale.forLanguageTag("hi");
    public static final Locale SPANISH = Locale.forLanguageTag("es");
    public static final List<Locale> SUPPORTED = List.of(Locale.ENGLISH, HINDI, SPANISH);

    /** The query parameter that picks a language. */
    public static final String LANGUAGE_PARAMETER = "lang";
    static final String LANGUAGE_COOKIE = "rentalhub-lang";

    /** The bean must be called "localeResolver": that is the name the DispatcherServlet looks up. */
    @Bean
    public LocaleResolver localeResolver() {
        return new SupportedLocaleResolver();
    }

    /** The one language of the three that best matches, or English. */
    static Locale supportedOrEnglish(Locale requested) {
        if (requested == null) {
            return Locale.ENGLISH;
        }
        return SUPPORTED.stream()
                .filter(supported -> supported.getLanguage().equals(requested.getLanguage()))
                .findFirst()
                .orElse(Locale.ENGLISH);
    }

    /** The best of the three for an Accept-Language header such as "hi-IN,hi;q=0.9,en;q=0.8". */
    static Locale fromAcceptLanguage(String header) {
        if (header == null || header.isBlank()) {
            return Locale.ENGLISH;
        }
        try {
            Locale best = Locale.lookup(Locale.LanguageRange.parse(header), SUPPORTED);
            return best == null ? Locale.ENGLISH : best;
        } catch (IllegalArgumentException malformed) {
            return Locale.ENGLISH;
        }
    }

    /**
     * A cookie-remembering resolver that only ever answers with a language the app speaks.
     *
     * A cookie rather than the HTTP session: it works the same for the API and for the pages,
     * and choosing a language does not make the server keep anything.
     */
    static final class SupportedLocaleResolver extends CookieLocaleResolver {

        SupportedLocaleResolver() {
            super(LANGUAGE_COOKIE);
            setCookieMaxAge(Duration.ofDays(365));
            setCookieSameSite("Lax");
            setDefaultLocaleFunction(request -> fromAcceptLanguage(request.getHeader("Accept-Language")));
        }

        @Override
        public Locale resolveLocale(HttpServletRequest request) {
            return supportedOrEnglish(super.resolveLocale(request));
        }

        /**
         * Must stay lazy, like Spring's own: the language can still change after this context
         * is handed out, and everything that reads it later (bean validation,
         * LocaleContextHolder) reads it through this object. Resolving once, here, froze the
         * language before ?lang= could change it.
         */
        @Override
        public LocaleContext resolveLocaleContext(HttpServletRequest request) {
            LocaleContext resolved = super.resolveLocaleContext(request);
            return new TimeZoneAwareLocaleContext() {
                @Override
                public Locale getLocale() {
                    return supportedOrEnglish(resolved.getLocale());
                }

                @Override
                public TimeZone getTimeZone() {
                    return resolved instanceof TimeZoneAwareLocaleContext zoned ? zoned.getTimeZone() : null;
                }
            };
        }

        /** ?lang=fr is not remembered: the choice must be one the app can honour. */
        @Override
        public void setLocaleContext(HttpServletRequest request, HttpServletResponse response,
                                     LocaleContext localeContext) {
            Locale requested = localeContext == null ? null : localeContext.getLocale();
            if (requested == null) {
                super.setLocaleContext(request, response, null);
                return;
            }
            Locale supported = supportedOrEnglish(requested);
            if (supported.getLanguage().equals(requested.getLanguage())) {
                // "hi-IN" is remembered as "hi": the region changes nothing the app can show.
                super.setLocaleContext(request, response, () -> supported);
            }
        }
    }
}
