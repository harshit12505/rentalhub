package com.rentalhub.exception;

import com.rentalhub.support.TestMessages;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.http.ProblemDetail;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(TestMessages.source());

    private final PropertyValidationException missingPlotArea = PropertyValidationException.onField(
            "attributes[plotAreaSqm]",
            "property.attribute.required",
            new DefaultMessageSourceResolvable("property.attribute.plotAreaSqm"));

    @Test
    @DisplayName("a message key becomes a readable problem detail, including a translated argument")
    void resolvesKeyAndNestedLabel() {
        ProblemDetail problem = handler.handlePropertyValidation(missingPlotArea, Locale.ENGLISH);

        assertThat(problem.getStatus()).isEqualTo(400);
        assertThat(problem.getTitle()).isEqualTo("The request was not valid");
        assertThat(problem.getDetail()).isEqualTo("Plot area (m²) is required for this type of property.");
        assertThat(problem.getProperties())
                .containsEntry("field", "attributes[plotAreaSqm]")
                .containsEntry("messageKey", "property.attribute.required");
    }

    @Test
    @DisplayName("a language with no translation file falls back to English")
    void unknownLanguageFallsBackToEnglish() {
        ProblemDetail problem = handler.handlePropertyValidation(missingPlotArea, Locale.FRENCH);

        assertThat(problem.getDetail()).isEqualTo("Plot area (m²) is required for this type of property.");
    }
}
