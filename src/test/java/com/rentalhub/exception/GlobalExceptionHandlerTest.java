package com.rentalhub.exception;

import com.rentalhub.domain.model.Villa;
import com.rentalhub.support.TestMessages;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.http.ProblemDetail;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

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
        ProblemDetail problem = handler.handleInvalidRequest(missingPlotArea, Locale.ENGLISH);

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
        ProblemDetail problem = handler.handleInvalidRequest(missingPlotArea, Locale.FRENCH);

        assertThat(problem.getDetail()).isEqualTo("Plot area (m²) is required for this type of property.");
    }

    @Test
    @DisplayName("a booking rule is rendered the same way as a listing rule")
    void bookingRuleIsA400WithField() {
        ProblemDetail problem = handler.handleInvalidRequest(
                InvalidRequestException.onField("checkOut", "booking.checkOut.beforeCheckIn"), Locale.ENGLISH);

        assertThat(problem.getStatus()).isEqualTo(400);
        assertThat(problem.getDetail()).isEqualTo("Check-out must be at least one day after check-in.");
        assertThat(problem.getProperties()).containsEntry("field", "checkOut");
    }

    @Test
    @DisplayName("not found, not allowed and conflict become 404, 403 and 409, with ids printed plainly")
    void statusMapping() {
        ProblemDetail notFound = handler.handleNotFound(
                new ResourceNotFoundException("property.notFound", 12345L), Locale.ENGLISH);
        ProblemDetail notAllowed = handler.handleNotAllowed(
                new OperationNotAllowedException("property.notOwner"), Locale.ENGLISH);
        ProblemDetail conflict = handler.handleConflict(
                new ConflictException("property.delete.hasBookings"), Locale.ENGLISH);

        assertThat(notFound.getStatus()).isEqualTo(404);
        // {0,number,#} in the message: an id is not a quantity, so no "12,345".
        assertThat(notFound.getDetail()).isEqualTo("There is no listing with id 12345.");
        assertThat(notAllowed.getStatus()).isEqualTo(403);
        assertThat(conflict.getStatus()).isEqualTo(409);
        assertThat(conflict.getProperties()).containsEntry("messageKey", "property.delete.hasBookings");
    }

    @Test
    @DisplayName("losing an optimistic-locking race is a 409 the client can retry, not a 500")
    void concurrentUpdateIs409() {
        ProblemDetail problem = handler.handleConcurrentUpdate(
                new ObjectOptimisticLockingFailureException(Villa.class, 1L), Locale.ENGLISH);

        assertThat(problem.getStatus()).isEqualTo(409);
        assertThat(problem.getDetail())
                .isEqualTo("Someone else changed this at the same moment. Reload it and try again.");
        assertThat(problem.getProperties()).containsEntry("messageKey", "error.concurrentUpdate");
    }
}
