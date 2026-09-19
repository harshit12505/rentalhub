package com.rentalhub.web.mvc;

import com.rentalhub.ai.AiAvailability;
import com.rentalhub.ai.RecommendationService;
import com.rentalhub.domain.model.enums.Currency;
import com.rentalhub.dto.RecommendationView;
import com.rentalhub.service.ReviewService;
import com.rentalhub.web.DemoSession;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Ask for somewhere to stay in plain English, and see both the answer and the listings it is
 * about.
 *
 * The answer and the cards are shown together on purpose: the text is written from those
 * listings only, and anyone reading it can check it against them. When the AI is not
 * configured, the page says so before anything is asked, as well as in the answer.
 */
@Controller
public class RecommendationPageController {

    /** As for the REST endpoint: long enough for a real request, too short to paste a book into the prompt. */
    private static final int LONGEST_QUESTION = 500;

    private final RecommendationService recommendations;
    private final AiAvailability availability;
    private final ReviewService reviews;

    public RecommendationPageController(RecommendationService recommendations,
                                        AiAvailability availability,
                                        ReviewService reviews) {
        this.recommendations = recommendations;
        this.availability = availability;
        this.reviews = reviews;
    }

    @GetMapping("/recommendations")
    public String ask(@RequestParam(required = false) String q,
                      @RequestParam(required = false) Currency currency,
                      HttpServletRequest request,
                      Model model) {
        model.addAttribute("aiReady", availability.ready());
        model.addAttribute("q", q);
        Long userId = DemoSession.userId(request);
        if (userId != null && q != null && !q.isBlank()) {
            String question = q.strip();
            if (question.length() > LONGEST_QUESTION) {
                question = question.substring(0, LONGEST_QUESTION);
            }
            RecommendationView result = recommendations.recommend(question, userId, currency);
            model.addAttribute("result", result);
            model.addAttribute("ratings", reviews.ratingsFor(
                    result.suggestions().stream().map(suggestion -> suggestion.listing().id()).toList()));
        }
        return "recommendations";
    }
}
