package com.rentalhub.support;

import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The value a setting in application.yml falls back to when its environment variable is not
 * set: for {@code cron: "${STALE_LISTINGS_CRON:0 15 3 * * *}"}, that is {@code 0 15 3 * * *}.
 *
 * The integration tests override some of these (the jobs are switched off there), so without
 * a test that reads the defaults, a broken one would first be noticed in production.
 */
public final class ConfiguredDefaults {

    /** ${VARIABLE:default}: everything after the first colon. */
    private static final Pattern PLACEHOLDER_DEFAULT = Pattern.compile("\\$\\{[^:}]+:(.*)}");

    private ConfiguredDefaults() {
    }

    public static String of(String property) {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        String configured = Objects.requireNonNull(Objects.requireNonNull(yaml.getObject()).getProperty(property),
                () -> property + " is not in application.yml");
        Matcher placeholder = PLACEHOLDER_DEFAULT.matcher(configured);
        if (!placeholder.matches()) {
            throw new AssertionError(property + " is configured as " + configured + ", not as ${VARIABLE:default}");
        }
        return placeholder.group(1);
    }
}
