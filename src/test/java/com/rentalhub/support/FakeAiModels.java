package com.rentalhub.support;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gemini, replaced by arithmetic.
 *
 * The AI tests must prove the wiring, the SQL and the safety checks, not that Google can
 * embed a sentence. A real key would make the build need a secret, cost quota, answer
 * differently every run, and fail whenever the network does, which is the opposite of a test.
 *
 * So both models are faked, and the rest of the phase is real: a real PgVectorStore writes
 * real vectors into the real pgvector container, and the taste vector really is computed by
 * Postgres.
 */
@TestConfiguration(proxyBeanMethods = false)
public class FakeAiModels {

    /** What the vector column holds; the same number as in application.yml and V4. */
    public static final int DIMENSIONS = 768;

    @Bean
    EmbeddingModel embeddingModel() {
        return new BagOfWordsEmbeddingModel();
    }

    @Bean
    FakeChatModel chatModel() {
        return new FakeChatModel();
    }

    /**
     * An embedding model with no model in it: each word is counted into one of the dimensions,
     * and the vector is scaled to length 1.
     *
     * That is enough for the tests to mean something, because cosine similarity then really
     * does measure how many words two texts share: "quiet beach house" is closer to a beach
     * listing than to a ski cabin, deterministically and with no network.
     */
    static final class BagOfWordsEmbeddingModel implements EmbeddingModel {

        private static final Pattern WORDS = Pattern.compile("[^\\p{IsAlphabetic}0-9]+");

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            List<Embedding> embeddings = new ArrayList<>();
            List<String> texts = request.getInstructions();
            for (int index = 0; index < texts.size(); index++) {
                embeddings.add(new Embedding(vectorOf(texts.get(index)), index));
            }
            return new EmbeddingResponse(embeddings);
        }

        @Override
        public float[] embed(Document document) {
            return vectorOf(document.getText());
        }

        @Override
        public int dimensions() {
            return DIMENSIONS;
        }

        static float[] vectorOf(String text) {
            float[] vector = new float[DIMENSIONS];
            // A constant "this is English about a place to stay" direction. Real embeddings of
            // two listings are never at right angles to each other, and a vector store that
            // drops everything with a similarity of exactly zero would hide half the index.
            vector[0] = 1f;
            for (String word : WORDS.split(text == null ? "" : text.toLowerCase(Locale.ROOT))) {
                if (!word.isBlank()) {
                    vector[Math.floorMod(word.hashCode(), DIMENSIONS)] += 1f;
                }
            }
            double length = 0;
            for (float value : vector) {
                length += value * value;
            }
            if (length == 0) {
                // pgvector cannot measure the angle of a vector with no direction.
                vector[0] = 1f;
                return vector;
            }
            float scale = (float) (1 / Math.sqrt(length));
            for (int i = 0; i < vector.length; i++) {
                vector[i] *= scale;
            }
            return vector;
        }
    }

    /**
     * A chat model that says whatever the test tells it to.
     *
     * Its default answer cites the first listing it was actually given, like a well-behaved
     * model. A test that wants to see the grounding check work makes it misbehave instead.
     */
    public static final class FakeChatModel implements ChatModel {

        private static final Pattern OFFERED_ID = Pattern.compile("^\\[(\\d+)]", Pattern.MULTILINE);

        private volatile Function<String, String> answer = FakeChatModel::citeTheFirstListing;
        private volatile String lastPrompt;

        /** Makes the next answers whatever {@code answer} builds from the prompt it was sent. */
        public void willAnswer(Function<String, String> answer) {
            this.answer = answer;
        }

        /** Makes the next answers a fixed sentence, whatever it was asked. */
        public void willSay(String text) {
            this.answer = prompt -> text;
        }

        public void behave() {
            this.answer = FakeChatModel::citeTheFirstListing;
        }

        /** The last prompt it was sent, for tests that check what the model was told. */
        public String lastPrompt() {
            return lastPrompt;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            lastPrompt = prompt.getContents();
            return new ChatResponse(List.of(new Generation(new AssistantMessage(answer.apply(lastPrompt)))));
        }

        private static String citeTheFirstListing(String prompt) {
            Matcher offered = OFFERED_ID.matcher(prompt);
            return offered.find()
                    ? "I would take [" + offered.group(1) + "] for this trip."
                    : "Nothing here fits, I am afraid.";
        }
    }
}
