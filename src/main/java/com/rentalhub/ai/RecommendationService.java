package com.rentalhub.ai;

import com.rentalhub.domain.model.enums.Currency;
import com.rentalhub.dto.PropertySummary;
import com.rentalhub.dto.RecommendationView;
import com.rentalhub.service.CurrencyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Answers a guest's question about where to stay.
 *
 * <b>What RAG means here.</b> RAG is "retrieval-augmented generation": instead of asking a
 * language model what it knows, you retrieve the facts first and let the model do nothing but
 * put them into sentences. The retrieval is {@link HybridRetriever}; the generation is one
 * call to Gemini with those listings, and only those listings, in the prompt. The model is
 * never asked a question it could answer from memory, because memory is where invented
 * listings come from.
 *
 * <b>The grounding check.</b> Each listing is shown to the model as {@code [id]}, and the
 * model is told to refer to them that way. Afterwards the answer is read back and every id in
 * it is checked against the ids that were actually sent. An id that was not sent is removed,
 * and if nothing genuine is left, the model's sentences are thrown away and the app writes the
 * answer instead. A model that makes something up cannot get it in front of a guest.
 *
 * <b>Degrading.</b> No key at all, no embeddings yet, quota exhausted, a question that is
 * really a sum: every one of those still answers, with a plainer answer and a flag saying so.
 */
@Slf4j
@Service
public class RecommendationService {

    /** How the model is told to point at a listing, and how the answer is checked afterwards. */
    private static final Pattern CITED_ID = Pattern.compile("\\[(\\d{1,18})]");

    private static final String INSTRUCTIONS = """
            You are the assistant of RentalHub, a holiday rental site.
            Recommend places to stay using ONLY the listings given to you below.
            Rules you must follow:
            - Refer to a listing by its bracketed id, for example [12], the first time you mention it.
            - Never invent a listing, a price, a place or a feature. If the listings do not suit
              the request, say so plainly and suggest what to change (budget, city, dates, guests).
            - Do not repeat the whole list. Pick the best two or three and say why they fit.
            - Keep it to at most four short sentences, in the same language as the question.
            - Prices are per night, in the currency shown; never convert them yourself.
            """;

    private final QueryParser parser;
    private final PreferenceProfileService profiles;
    private final HybridRetriever retriever;
    private final StatsService stats;
    private final AiAvailability availability;
    private final CurrencyService currencies;
    private final MessageSource messages;
    private final ObjectProvider<ChatModel> chatModel;

    RecommendationService(QueryParser parser,
                          PreferenceProfileService profiles,
                          HybridRetriever retriever,
                          StatsService stats,
                          AiAvailability availability,
                          CurrencyService currencies,
                          MessageSource messages,
                          ObjectProvider<ChatModel> chatModel) {
        this.parser = parser;
        this.profiles = profiles;
        this.retriever = retriever;
        this.stats = stats;
        this.availability = availability;
        this.currencies = currencies;
        this.messages = messages;
        this.chatModel = chatModel;
    }

    /**
     * Answers one question for one guest.
     *
     * @param question what they typed
     * @param userId   the acting user, whose own history the answer is shaped by
     * @param currency the currency to show prices in, or null for the default
     */
    public RecommendationView recommend(String question, long userId, Currency currency) {
        Currency displayCurrency = currencies.orDefault(currency);
        PreferenceProfile profile = profiles.forUser(userId);
        ParsedQuery query = parser.parse(question, profile);
        Locale locale = LocaleContextHolder.getLocale();

        if (query.intent() == ParsedQuery.Intent.STATS) {
            // A question about their own numbers: the database answers it exactly, and no
            // model is called at all. This works with no AI key.
            return new RecommendationView(query.text(), query.intent(),
                    stats.answer(query, profile, displayCurrency), List.of(), false, false, false);
        }

        HybridRetriever.Retrieval found = retriever.retrieve(query, profile);
        List<RecommendationView.Suggestion> suggestions = found.matches().stream()
                // Only when a currency was actually asked for, as everywhere since phase 5: a
                // displayPrice that repeats the real price at a rate of 1 says nothing.
                .map(match -> new RecommendationView.Suggestion(
                        currencies.inCurrency(match.listing(), currency), match.similarity()))
                .toList();

        if (suggestions.isEmpty()) {
            return new RecommendationView(query.text(), query.intent(), say("ai.noMatches", locale),
                    List.of(), false, found.semantic(), found.exchangeRatesUnavailable());
        }

        String written = availability.canWrite() ? write(query, profile, suggestions) : null;
        return new RecommendationView(
                query.text(),
                query.intent(),
                written != null ? written : say(fallbackKey(found), locale, suggestions.size()),
                suggestions,
                written != null,
                found.semantic(),
                found.exchangeRatesUnavailable());
    }

    /**
     * One call to the model, with the listings in the prompt, and the answer checked before
     * it is returned. Null when the model cannot be used or its answer did not survive the
     * check, which leaves the caller to write the answer itself.
     */
    private String write(ParsedQuery query, PreferenceProfile profile,
                         List<RecommendationView.Suggestion> suggestions) {
        ChatModel model = chatModel.getIfAvailable();
        if (model == null) {
            return null;
        }
        Set<Long> offered = suggestions.stream()
                .map(suggestion -> suggestion.listing().id())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        try {
            ChatResponse response = model.call(new Prompt(List.of(
                    new SystemMessage(INSTRUCTIONS),
                    new UserMessage(userMessage(query, profile, suggestions)))));
            String answer = textOf(response);
            return answer == null ? null : grounded(answer, offered);
        } catch (RuntimeException failed) {
            // Quota, timeout, a revoked key: the listings are still good, so the caller
            // writes a plain answer about them rather than failing the request.
            log.atWarn().setMessage("ai.answer.failed")
                    .addKeyValue("reason", failed.getClass().getSimpleName())
                    .addKeyValue("error", failed.getMessage())
                    .setCause(failed)
                    .log();
            return null;
        }
    }

    /**
     * The question, the guest in one line, and the listings the answer may talk about.
     *
     * The profile is a sentence rather than their rows: the model needs to know they like
     * quiet places in Goa for two, not who they are or what they paid.
     */
    private String userMessage(ParsedQuery query, PreferenceProfile profile,
                               List<RecommendationView.Suggestion> suggestions) {
        StringBuilder prompt = new StringBuilder("Question: ").append(query.text()).append("\n\n");
        prompt.append("About this guest: ").append(profile.summary()).append("\n\n");
        if (query.city() != null || query.maxPrice() != null || query.guests() != null) {
            prompt.append("Filters already applied to the list below:");
            if (query.city() != null) {
                prompt.append(" city=").append(query.city()).append(';');
            }
            if (query.maxPrice() != null) {
                prompt.append(" maxPricePerNight=").append(query.maxPrice().toPlainString())
                        .append(' ').append(query.currency()).append(';');
            }
            if (query.guests() != null) {
                prompt.append(" guests=").append(query.guests()).append(';');
            }
            prompt.append("\n\n");
        }
        prompt.append("Listings you may recommend:\n");
        suggestions.forEach(suggestion -> prompt.append(listingLine(suggestion.listing())).append('\n'));
        return prompt.toString();
    }

    private String listingLine(PropertySummary listing) {
        return "[%d] %s — %s, %s — %s %s per night — sleeps %d, %d bedroom(s), %d bathroom(s) — type %s"
                .formatted(listing.id(), listing.title(), listing.city(), listing.country(),
                        listing.pricePerNight().toPlainString(), listing.currency(),
                        listing.maxGuests(), listing.bedrooms(), listing.bathrooms(), listing.type());
    }

    /**
     * Removes any listing id the model made up, and refuses the whole answer if none of the
     * ids in it were real. An answer that mentions no id at all is kept: "nothing here fits
     * your budget" is a good answer, and it invents nothing.
     */
    private String grounded(String answer, Set<Long> offered) {
        Matcher cited = CITED_ID.matcher(answer);
        Set<Long> invented = new LinkedHashSet<>();
        int genuine = 0;
        while (cited.find()) {
            long id = Long.parseLong(cited.group(1));
            if (offered.contains(id)) {
                genuine++;
            } else {
                invented.add(id);
            }
        }
        if (invented.isEmpty()) {
            return answer;
        }
        log.atWarn().setMessage("ai.answer.ungrounded")
                .addKeyValue("invented", invented)
                .addKeyValue("offered", offered)
                .log();
        if (genuine == 0) {
            // Every listing it named was imagined. Nothing here can be trusted.
            return null;
        }
        String cleaned = answer;
        for (Long id : invented) {
            cleaned = cleaned.replace("[" + id + "]", "");
        }
        return cleaned.replaceAll(" {2,}", " ").strip();
    }

    private static String textOf(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return null;
        }
        String text = response.getResult().getOutput().getText();
        return text == null || text.isBlank() ? null : text.strip();
    }

    /** What the app says when it wrote the answer itself: why, in the guest's language. */
    private String fallbackKey(HybridRetriever.Retrieval found) {
        if (!availability.canSearch()) {
            return "ai.notConfigured";
        }
        return found.semantic() ? "ai.listOnly" : "ai.listOnly.noEmbeddings";
    }

    private String say(String key, Locale locale, Object... arguments) {
        return messages.getMessage(key, arguments, locale);
    }
}
