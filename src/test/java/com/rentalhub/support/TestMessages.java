package com.rentalhub.support;

import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * The real messages.properties, loaded the way application.yml configures it, for
 * unit tests that need to prove a message key exists and renders without starting
 * Spring Boot. Missing keys throw NoSuchMessageException, which is the point.
 */
public final class TestMessages {

    private static final MessageSource SOURCE = create();

    private TestMessages() {
    }

    public static MessageSource source() {
        return SOURCE;
    }

    public static String english(MessageSourceResolvable resolvable) {
        return SOURCE.getMessage(resolvable, Locale.ENGLISH);
    }

    public static String english(String key, Object... arguments) {
        return SOURCE.getMessage(key, arguments, Locale.ENGLISH);
    }

    private static MessageSource create() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding(StandardCharsets.UTF_8.name());
        source.setFallbackToSystemLocale(false);
        return source;
    }
}
