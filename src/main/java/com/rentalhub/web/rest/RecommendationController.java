package com.rentalhub.web.rest;

import com.rentalhub.ai.RecommendationService;
import com.rentalhub.domain.model.enums.Currency;
import com.rentalhub.dto.RecommendationView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ask for somewhere to stay in plain English.
 *
 * A GET, because asking a question changes nothing: the same question gives the same kind of
 * answer, and a client may cache or bookmark it. The question travels as {@code q}.
 *
 * This endpoint always answers 200, even with no AI key, no embeddings and no quota left. The
 * body then holds a plainer answer and says so ({@code aiUsed}, {@code semantic}), because the
 * rule for this project is that a missing credential costs you that feature and nothing else.
 */
@RestController
@RequestMapping("/api/recommendations")
public class RecommendationController {

    /** Long enough for a real request, short enough that nobody pastes a book into the prompt. */
    private static final int LONGEST_QUESTION = 500;

    private final RecommendationService recommendations;

    public RecommendationController(RecommendationService recommendations) {
        this.recommendations = recommendations;
    }

    /**
     * @param q        the question, for example "somewhere quiet in Goa for 2 under 5000"
     * @param currency optional: show the prices converted into this currency too
     */
    @GetMapping
    public RecommendationView ask(@RequestParam String q,
                                  @RequestHeader(ApiHeaders.DEMO_USER_ID) long userId,
                                  @RequestParam(required = false) Currency currency) {
        String question = q.strip();
        if (question.length() > LONGEST_QUESTION) {
            question = question.substring(0, LONGEST_QUESTION);
        }
        return recommendations.recommend(question, userId, currency);
    }
}
