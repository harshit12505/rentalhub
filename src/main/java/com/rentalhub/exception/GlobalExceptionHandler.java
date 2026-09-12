package com.rentalhub.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Locale;

/**
 * Turns exceptions thrown by REST controllers into RFC 9457 "problem detail" JSON,
 * with the human-readable text resolved in the caller's language.
 *
 * Scoped to {@code @RestController}s only: Thymeleaf page controllers show errors on
 * the form itself rather than returning JSON, so they must not be caught here.
 *
 * The {@code Locale} parameter is filled in by Spring's LocaleResolver (the browser's
 * Accept-Language header today, plus a ?lang= override in phase 7), so adding a
 * language never requires touching this class.
 */
@Slf4j
@RestControllerAdvice(annotations = RestController.class)
public class GlobalExceptionHandler {

    private final MessageSource messages;

    public GlobalExceptionHandler(MessageSource messages) {
        this.messages = messages;
    }

    @ExceptionHandler(PropertyValidationException.class)
    public ProblemDetail handlePropertyValidation(PropertyValidationException ex, Locale locale) {
        log.debug("Listing rejected: messageKey={} field={}", ex.getMessageKey(), ex.getField());
        ProblemDetail problem = localizedProblem(HttpStatus.BAD_REQUEST, ex, locale);
        if (ex.getField() != null) {
            problem.setProperty("field", ex.getField());
        }
        return problem;
    }

    private ProblemDetail localizedProblem(HttpStatus status, LocalizedException ex, Locale locale) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, messages.getMessage(ex, locale));
        problem.setTitle(messages.getMessage("error.title." + status.value(), null, status.getReasonPhrase(), locale));
        // The key lets API clients react to a specific error without parsing translated text.
        problem.setProperty("messageKey", ex.getMessageKey());
        return problem;
    }
}
