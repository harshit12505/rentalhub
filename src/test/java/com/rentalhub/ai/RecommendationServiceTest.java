package com.rentalhub.ai;

import com.rentalhub.domain.model.enums.Currency;
import com.rentalhub.domain.model.enums.PropertyType;
import com.rentalhub.domain.repository.PropertyRepository;
import com.rentalhub.dto.PropertySummary;
import com.rentalhub.dto.RecommendationView;
import com.rentalhub.service.CurrencyService;
import com.rentalhub.support.TestMessages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The rules around the model call: when it is made at all, and what happens to what it says.
 *
 * Everything the model could get wrong is checked here, with a fake model that misbehaves on
 * purpose. A real key is never needed to prove this, which is the point: the safety net is in
 * the code, not in the provider.
 */
class RecommendationServiceTest {

    private final PropertyRepository properties = mock(PropertyRepository.class);
    private final CurrencyService currencies = mock(CurrencyService.class);
    private final PreferenceProfileService profiles = mock(PreferenceProfileService.class);
    private final HybridRetriever retriever = mock(HybridRetriever.class);
    private final StatsService stats = mock(StatsService.class);
    private final AiAvailability availability = mock(AiAvailability.class);
    private final ChatModel chatModel = mock(ChatModel.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<ChatModel> chatModels = mock(ObjectProvider.class);

    private RecommendationService recommendations;

    @BeforeEach
    void setUp() {
        when(properties.findDistinctActiveCities()).thenReturn(List.of("Goa"));
        when(currencies.orDefault(any())).thenReturn(Currency.INR);
        when(currencies.inCurrency(any(PropertySummary.class), any())).thenAnswer(call -> call.getArgument(0));
        when(profiles.forUser(anyLong())).thenReturn(profile());
        when(availability.canSearch()).thenReturn(true);
        when(availability.canWrite()).thenReturn(true);
        when(chatModels.getIfAvailable()).thenReturn(chatModel);

        recommendations = new RecommendationService(new QueryParser(properties, currencies), profiles, retriever,
                stats, availability, currencies, TestMessages.source(), chatModels);
    }

    @Test
    @DisplayName("a question about the guest's own numbers never reaches the model")
    void statisticsAreAnsweredBySql() {
        when(stats.answer(any(), any(), any())).thenReturn("You have spent 12,000.00 INR on 2 paid booking(s).");

        RecommendationView view = recommendations.recommend("how much have I spent on bookings?", 7L, null);

        assertThat(view.intent()).isEqualTo(ParsedQuery.Intent.STATS);
        assertThat(view.answer()).isEqualTo("You have spent 12,000.00 INR on 2 paid booking(s).");
        assertThat(view.aiUsed()).isFalse();
        assertThat(view.suggestions()).isEmpty();
        verifyNoInteractions(chatModel, retriever);
    }

    @Test
    @DisplayName("an answer that only cites listings it was given is passed through")
    void keepsAGroundedAnswer() {
        givenRetrieved(1L, 2L);
        givenTheModelSays("I would take [1] for the garden, or [2] if you want to be closer to town.");

        RecommendationView view = recommendations.recommend("somewhere quiet in Goa", 7L, null);

        assertThat(view.aiUsed()).isTrue();
        assertThat(view.semantic()).isTrue();
        assertThat(view.answer()).contains("[1]").contains("[2]");
        assertThat(view.suggestions()).hasSize(2);
    }

    @Test
    @DisplayName("a listing the model invented is cut out of the answer")
    void removesAnInventedListing() {
        givenRetrieved(1L, 2L);
        givenTheModelSays("Try [1], and [999] is also lovely.");

        RecommendationView view = recommendations.recommend("somewhere quiet in Goa", 7L, null);

        assertThat(view.answer()).contains("[1]").doesNotContain("999");
        assertThat(view.aiUsed()).isTrue();
    }

    @Test
    @DisplayName("if every listing it named was invented, the whole answer is thrown away")
    void refusesAnUngroundedAnswer() {
        givenRetrieved(1L, 2L);
        givenTheModelSays("You will love [900] and [901].");

        RecommendationView view = recommendations.recommend("somewhere quiet in Goa", 7L, null);

        assertThat(view.aiUsed()).as("the app wrote this answer, not the model").isFalse();
        assertThat(view.answer()).isEqualTo(TestMessages.english("ai.listOnly", 2));
        assertThat(view.suggestions())
                .as("the listings themselves were never in doubt")
                .hasSize(2);
    }

    @Test
    @DisplayName("with no key at all the listings still come back, with a plain answer saying why")
    void degradesWithoutAKey() {
        when(availability.canSearch()).thenReturn(false);
        when(availability.canWrite()).thenReturn(false);
        when(chatModels.getIfAvailable()).thenReturn(null);
        when(retriever.retrieve(any(), any())).thenReturn(new HybridRetriever.Retrieval(
                List.of(new HybridRetriever.Match(listing(1L), 0.5, null)), false, false));

        RecommendationView view = recommendations.recommend("somewhere quiet in Goa", 7L, null);

        assertThat(view.aiUsed()).isFalse();
        assertThat(view.semantic()).isFalse();
        assertThat(view.answer()).isEqualTo(TestMessages.english("ai.notConfigured", 1));
        assertThat(view.suggestions()).hasSize(1);
        verifyNoInteractions(chatModel);
    }

    @Test
    @DisplayName("nothing to suggest is said plainly, and costs no model call")
    void saysWhenNothingMatches() {
        when(retriever.retrieve(any(), any()))
                .thenReturn(new HybridRetriever.Retrieval(List.of(), true, false));

        RecommendationView view = recommendations.recommend("a castle in Goa under 10", 7L, null);

        assertThat(view.answer()).isEqualTo(TestMessages.english("ai.noMatches"));
        assertThat(view.suggestions()).isEmpty();
        verifyNoInteractions(chatModel);
    }

    private void givenRetrieved(long... ids) {
        List<HybridRetriever.Match> matches = new java.util.ArrayList<>();
        for (long id : ids) {
            matches.add(new HybridRetriever.Match(listing(id), 0.9, 0.9));
        }
        when(retriever.retrieve(any(), any())).thenReturn(new HybridRetriever.Retrieval(matches, true, false));
    }

    private void givenTheModelSays(String answer) {
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(answer)))));
    }

    private static PropertySummary listing(long id) {
        return new PropertySummary(id, PropertyType.VILLA, "Sea Breeze Villa " + id, "Goa", "India",
                new BigDecimal("8500.00"), Currency.INR, 6, 3, 2, null, null);
    }

    private static PreferenceProfile profile() {
        return new PreferenceProfile(7L, List.of(), List.of(), Currency.INR, null, null, null, List.of(), 0, null);
    }
}
