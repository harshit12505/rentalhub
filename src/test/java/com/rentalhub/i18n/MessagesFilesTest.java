package com.rentalhub.i18n;

import com.rentalhub.domain.model.enums.BookingStatus;
import com.rentalhub.domain.model.enums.PaymentStatus;
import com.rentalhub.domain.model.enums.PropertyType;
import com.rentalhub.domain.model.enums.UserRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three message files stay in step, and the translations are real.
 *
 * The spec's words: "translations must be real, not English copied three times". A test
 * cannot judge Hindi, but it can catch every mechanical way a translation goes wrong: a key
 * added to English and forgotten elsewhere, a {0} lost or renumbered in translation (which
 * silently drops the number from the sentence), a value left in English, or an apostrophe that
 * MessageFormat would swallow. And it checks the other direction too: every key the code
 * throws must exist.
 */
class MessagesFilesTest {

    private static final Path RESOURCES = Path.of("src", "main", "resources");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{[^{}]*}");
    private static final Pattern DEVANAGARI = Pattern.compile("[\\u0900-\\u097F]");

    /**
     * Spanish values that really are the same word as the English: a villa is a "villa", gas is
     * "gas", and no is "no". Each one is listed on purpose, so nothing else can slip through.
     */
    private static final Set<String> SAME_IN_SPANISH = Set.of(
            "property.type.VILLA", "property.attribute.heatingType.GAS", "attribute.value.false");

    private static final Properties ENGLISH = load("messages.properties");
    private static final Properties HINDI = load("messages_hi.properties");
    private static final Properties SPANISH = load("messages_es.properties");

    @Test
    @DisplayName("Hindi and Spanish have exactly the English keys: none missing, none extra")
    void sameKeys() {
        assertThat(HINDI.stringPropertyNames()).isEqualTo(ENGLISH.stringPropertyNames());
        assertThat(SPANISH.stringPropertyNames()).isEqualTo(ENGLISH.stringPropertyNames());
    }

    @Test
    @DisplayName("every translation keeps exactly the English placeholders")
    void samePlaceholders() {
        for (String key : ENGLISH.stringPropertyNames()) {
            Set<String> expected = placeholders(ENGLISH.getProperty(key));
            assertThat(placeholders(HINDI.getProperty(key))).as("Hindi %s", key).isEqualTo(expected);
            assertThat(placeholders(SPANISH.getProperty(key))).as("Spanish %s", key).isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("nothing is left in English: Hindi is written in Devanagari, Spanish differs")
    void translationsAreReal() {
        for (String key : ENGLISH.stringPropertyNames()) {
            assertThat(DEVANAGARI.matcher(HINDI.getProperty(key)).find())
                    .as("Hindi %s is in Devanagari: %s", key, HINDI.getProperty(key))
                    .isTrue();
            if (!SAME_IN_SPANISH.contains(key)) {
                assertThat(SPANISH.getProperty(key))
                        .as("Spanish %s is translated", key)
                        .isNotEqualTo(ENGLISH.getProperty(key));
            }
        }
    }

    @Test
    @DisplayName("every message with {0}-style arguments parses, in every language, with no stray apostrophe")
    void messageFormatsParse() {
        for (Map.Entry<String, Properties> file : Map.of("en", ENGLISH, "hi", HINDI, "es", SPANISH).entrySet()) {
            for (String key : file.getValue().stringPropertyNames()) {
                String value = file.getValue().getProperty(key);
                if (value.matches("(?s).*\\{\\d.*")) {
                    // A lone ' starts a quoted section in MessageFormat and eats the rest of the text.
                    assertThat(value).as("%s %s", file.getKey(), key).doesNotContain("'");
                    new MessageFormat(value, Locale.forLanguageTag(file.getKey()));
                }
            }
        }
    }

    @Test
    @DisplayName("every message key the code uses exists in the files")
    void everyKeyUsedInCodeExists() throws IOException {
        Pattern used = Pattern.compile(
                "(?:Exception|onField|notFound|say|getMessage)\\((?:\"[a-zA-Z]+\", )?\"([a-z][a-zA-Z]*(?:\\.[a-zA-Z0-9]+)+)\""
                        + "|message = \"\\{([a-z][a-zA-Z]*(?:\\.[a-zA-Z0-9]+)+)}\"");
        Set<String> keys = new TreeSet<>();
        try (Stream<Path> sources = Files.walk(Path.of("src", "main", "java"))) {
            for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                Matcher found = used.matcher(Files.readString(source));
                while (found.find()) {
                    keys.add(found.group(1) != null ? found.group(1) : found.group(2));
                }
            }
        }
        assertThat(keys).as("keys found in the code").hasSizeGreaterThan(50);
        Set<String> missing = new HashSet<>(keys);
        missing.removeAll(ENGLISH.stringPropertyNames());
        assertThat(missing).as("keys used in code but missing from messages.properties").isEmpty();
    }

    /**
     * Thymeleaf does not fail on a missing key: it prints ??key_en?? into the page. So the keys the
     * templates name are checked here, and the ones a template builds from a value
     * (#{bookings.status.__${b.status}__}) are checked for every value that enum can take.
     */
    @Test
    @DisplayName("every message key the page templates use exists, including those built from an enum value")
    void everyKeyUsedInTemplatesExists() throws IOException {
        Pattern comment = Pattern.compile("(?s)<!--/\\*.*?\\*/-->");
        Pattern used = Pattern.compile("#\\{([a-zA-Z][a-zA-Z0-9]*(?:\\.[a-zA-Z0-9_]+)+)[}(]");
        Set<String> keys = new TreeSet<>();
        try (Stream<Path> templates = Files.walk(RESOURCES.resolve("templates"))) {
            for (Path template : templates.filter(path -> path.toString().endsWith(".html")).toList()) {
                String html = comment.matcher(Files.readString(template)).replaceAll("");
                used.matcher(html).results().forEach(match -> keys.add(match.group(1)));
            }
        }
        assertThat(keys).as("keys found in the templates").hasSizeGreaterThan(80);
        for (Map.Entry<String, Enum<?>[]> family : Map.<String, Enum<?>[]>of(
                "role.", UserRole.values(),
                "bookings.status.", BookingStatus.values(),
                "payment.status.", PaymentStatus.values(),
                "property.type.", PropertyType.values()).entrySet()) {
            for (Enum<?> value : family.getValue()) {
                keys.add(family.getKey() + value.name());
            }
        }
        Set<String> missing = new TreeSet<>(keys);
        missing.removeAll(ENGLISH.stringPropertyNames());
        assertThat(missing).as("keys used in templates but missing from messages.properties").isEmpty();
    }

    private static Set<String> placeholders(String value) {
        Matcher matcher = PLACEHOLDER.matcher(value);
        Set<String> found = new TreeSet<>();
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return found;
    }

    private static Properties load(String file) {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(RESOURCES.resolve(file), StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + file, e);
        }
        return properties;
    }
}
