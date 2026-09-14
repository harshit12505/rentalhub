package com.rentalhub.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns exceptions thrown by REST controllers into RFC 9457 "problem detail" JSON,
 * with the human-readable text resolved in the caller's language.
 *
 * Extends Spring's ResponseEntityExceptionHandler, which already turns Spring MVC's
 * own errors (a missing header, a malformed id, unreadable JSON) into problem details,
 * so every error from the API has the same shape.
 *
 * Scoped to {@code @RestController}s only: Thymeleaf page controllers show errors on
 * the form itself rather than returning JSON, so they must not be caught here.
 *
 * The {@code Locale} is filled in by Spring's LocaleResolver (the browser's
 * Accept-Language header today, plus a ?lang= override in phase 7), so adding a
 * language never requires touching this class.
 */
@Slf4j
@RestControllerAdvice(annotations = RestController.class)
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    static final String CONCURRENT_UPDATE = "error.concurrentUpdate";

    private final MessageSource messages;

    public GlobalExceptionHandler(MessageSource messages) {
        this.messages = messages;
    }

    /** A business rule refused the request: a listing's type rules, a booking's date rules. */
    @ExceptionHandler(InvalidRequestException.class)
    public ProblemDetail handleInvalidRequest(InvalidRequestException ex, Locale locale) {
        log.debug("Request rejected: messageKey={} field={}", ex.getMessageKey(), ex.getField());
        ProblemDetail problem = localizedProblem(HttpStatus.BAD_REQUEST, ex, locale);
        if (ex.getField() != null) {
            problem.setProperty("field", ex.getField());
        }
        return problem;
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail handleNotFound(ResourceNotFoundException ex, Locale locale) {
        return localizedProblem(HttpStatus.NOT_FOUND, ex, locale);
    }

    @ExceptionHandler(OperationNotAllowedException.class)
    public ProblemDetail handleNotAllowed(OperationNotAllowedException ex, Locale locale) {
        return localizedProblem(HttpStatus.FORBIDDEN, ex, locale);
    }

    @ExceptionHandler(ConflictException.class)
    public ProblemDetail handleConflict(ConflictException ex, Locale locale) {
        return localizedProblem(HttpStatus.CONFLICT, ex, locale);
    }

    /**
     * Two requests changed the same rows at the same moment and this one lost: its
     * version check failed (optimistic locking), for example a host saving a listing just
     * as a guest booked it, or Postgres broke a deadlock by cancelling it. Nothing was
     * saved, so trying again is safe, hence 409 rather than 500. A booking that loses
     * such a race is retried by BookingService instead, which has its own message if it
     * keeps losing.
     */
    @ExceptionHandler(ConcurrencyFailureException.class)
    public ProblemDetail handleConcurrentUpdate(ConcurrencyFailureException ex, Locale locale) {
        log.info("request.conflict reason=concurrent-update error=\"{}\"", ex.getMessage());
        return problem(HttpStatus.CONFLICT, new DefaultMessageSourceResolvable(CONCURRENT_UPDATE),
                CONCURRENT_UPDATE, locale);
    }

    /**
     * A request body failed bean validation ({@code @Valid}). The validator has already
     * rendered each field's message in the request's language; they are all listed,
     * sorted by field, so a client can show every problem at once instead of one at a time.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        Locale locale = LocaleContextHolder.getLocale();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, messages.getMessage("error.validation.detail", null, locale));
        problem.setTitle(title(HttpStatus.BAD_REQUEST, locale));
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .sorted(Comparator.comparing(FieldError::getField))
                .map(error -> Map.of("field", error.getField(), "message", messages.getMessage(error, locale)))
                .toList();
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().headers(headers).body(problem);
    }

    private ProblemDetail localizedProblem(HttpStatus status, LocalizedException ex, Locale locale) {
        return problem(status, ex, ex.getMessageKey(), locale);
    }

    private ProblemDetail problem(HttpStatus status, MessageSourceResolvable message, String messageKey, Locale locale) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, messages.getMessage(message, locale));
        problem.setTitle(title(status, locale));
        // The key lets API clients react to a specific error without parsing translated text.
        problem.setProperty("messageKey", messageKey);
        return problem;
    }

    private String title(HttpStatus status, Locale locale) {
        return messages.getMessage("error.title." + status.value(), null, status.getReasonPhrase(), locale);
    }
}
