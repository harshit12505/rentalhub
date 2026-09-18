package com.rentalhub.ai;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Switches the AI off when there is no Gemini key.
 *
 * Without this, Spring AI would try to build a chat model, an embedding model, and a
 * PgVectorStore that takes the embedding model as a constructor argument. The Gemini client
 * refuses to be built with no key, and that failure happens while the context is being
 * created, so the application would not start at all. The spec's rule is absolute: a missing
 * key must never stop it starting.
 *
 * It runs before any bean exists, straight after the configuration files have been read (see
 * {@link #getOrder()}), because the key itself comes from application.yml. Its values are
 * added <em>last</em>, as defaults, so anything set on purpose (an environment variable, a
 * test's own properties) still wins.
 *
 * Registered in {@code META-INF/spring.factories}. Boot 4 looks for the new
 * {@code org.springframework.boot.EnvironmentPostProcessor} name there; the older
 * {@code org.springframework.boot.env} spelling, and its {@code .imports} file, are ignored.
 */
public class AiEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String API_KEY = "spring.ai.google.genai.api-key";
    private static final String SOURCE_NAME = "rentalhub-ai-off";
    private static final String EMBEDDING_CONNECTION =
            "org.springframework.ai.model.google.genai.autoconfigure.embedding."
                    + "GoogleGenAiEmbeddingConnectionAutoConfiguration";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String key = environment.getProperty(API_KEY, "");
        if (key != null && !key.isBlank()) {
            return;
        }
        Map<String, Object> off = new LinkedHashMap<>();
        off.put("spring.ai.model.chat", "none");
        off.put("spring.ai.model.embedding", "none");
        off.put("spring.ai.model.embedding.text", "none");
        // The vector store is built from the embedding model, so it goes too.
        off.put("spring.ai.vectorstore.type", "none");
        // The embedding connection has no switch of its own: it is configured unconditionally
        // and throws ("project-id must be set") when neither a key nor a Vertex project is
        // there. Nothing needs it once the embedding model is off, so it is excluded outright.
        off.put("spring.autoconfigure.exclude", EMBEDDING_CONNECTION);
        environment.getPropertySources().addLast(new MapPropertySource(SOURCE_NAME, off));
    }

    /** Just after the configuration files are loaded: the key is read from application.yml. */
    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }
}
