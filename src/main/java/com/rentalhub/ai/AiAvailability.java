package com.rentalhub.ai;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Whether the AI features can run, and what to say when they can't.
 *
 * With no Gemini key, {@link AiEnvironmentPostProcessor} switches off the chat model, the
 * embedding model and the vector store, so none of those beans exist at all. Everything in
 * this package asks here first and degrades politely instead of failing: the rule from the
 * spec is that a missing key costs you that feature and nothing else.
 *
 * The three parts are tracked separately because they fail separately. Embeddings and the
 * vector store are what search by meaning needs; the chat model only writes the answer.
 */
@Slf4j
@Component
public class AiAvailability {

    private final boolean chatModel;
    private final boolean embeddingModel;
    private final boolean vectorStore;

    AiAvailability(ObjectProvider<ChatModel> chatModel,
                   ObjectProvider<EmbeddingModel> embeddingModel,
                   ObjectProvider<VectorStore> vectorStore) {
        this.chatModel = chatModel.getIfAvailable() != null;
        this.embeddingModel = embeddingModel.getIfAvailable() != null;
        this.vectorStore = vectorStore.getIfAvailable() != null;
    }

    /** Listings can be embedded, and searched by meaning. */
    public boolean canSearch() {
        return embeddingModel && vectorStore;
    }

    /** An answer can be written about them. */
    public boolean canWrite() {
        return chatModel;
    }

    /** Both halves are there: the full recommendation feature. */
    public boolean ready() {
        return canSearch() && canWrite();
    }

    /** What a caller is told when the AI features are off. A message key, as everywhere else. */
    public String reason() {
        return ready() ? "ai.ready" : "ai.notConfigured";
    }

    @PostConstruct
    void report() {
        log.atInfo().setMessage("ai.mode")
                .addKeyValue("ready", ready())
                .addKeyValue("chatModel", chatModel)
                .addKeyValue("embeddingModel", embeddingModel)
                .addKeyValue("vectorStore", vectorStore)
                .log();
    }
}
