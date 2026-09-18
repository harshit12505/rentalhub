package com.rentalhub.exception;

import com.rentalhub.web.RequestIdFilter;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
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
import org.springframework.web.context.request.ServletWebRequest;
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
 * The {@code Locale} is filled in by Spring's LocaleResolver (?lang=, then the language
 * cookie, then Accept-Language; see config/WebConfig), so adding a language never requires
 * touching this class. Spring's own errors are translated too: their text comes from the
 * {@code problemDetail.<exception class>} keys in messages*.properties, and their title
 * from the same {@code error.title.<status>} keys as everything else.
 */
@Slf4j
@RestControllerAdvice(annotations = RestController.class)
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    static final String CONCURRENT_UPDATE = "error.concurrentUpdate";
    static final String UNEXPECTED = "error.unexpected";

    private final MessageSource messages;

    public GlobalExceptionHandler(MessageSource messages) {
        this.messages = messages;
    }

    /** A business rule refused the request: a listing's type rules, a booking's date rules. */
    @ExceptionHandler(InvalidRequestException.class)
    public ProblemDetail handleInvalidRequest(InvalidRequestException ex, Locale locale) {
        log.atDebug().setMessage("request.rejected")
                .addKeyValue("messageKey", ex.getMessageKey())
                .addKeyValue("field", ex.getField())
                .log();
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
     * The card was declined, or needs a check this API can't do. 402 Payment Required is the
     * status Stripe itself uses for card errors. Nothing was charged; the dates were released.
     */
    @ExceptionHandler(PaymentFailedException.class)
    public ProblemDetail handlePaymentFailed(PaymentFailedException ex, Locale locale) {
        return localizedProblem(HttpStatus.PAYMENT_REQUIRED, ex, locale);
    }

    /**
     * The payment provider couldn't take the payment. Nothing was charged, and trying again in
     * a while may work, which is what 503 and its Retry-After header (in seconds) say.
     */
    @ExceptionHandler(PaymentUnavailableException.class)
    public ResponseEntity<ProblemDetail> handlePaymentUnavailable(PaymentUnavailableException ex, Locale locale) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "60")
                .body(localizedProblem(HttpStatus.SERVICE_UNAVAILABLE, ex, locale));
    }

    /**
     * Photos can't be stored or read. When no storage is configured at all, retrying won't
     * help, so only a passing failure carries Retry-After.
     */
    @ExceptionHandler(ImageStorageUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleImageStorageUnavailable(ImageStorageUnavailableException ex,
                                                                       Locale locale) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE);
        if (ex.isTemporary()) {
            response.header(HttpHeaders.RETRY_AFTER, "60");
        }
        return response.body(localizedProblem(HttpStatus.SERVICE_UNAVAILABLE, ex, locale));
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
        log.atInfo().setMessage("request.conflict")
                .addKeyValue("reason", "concurrent-update")
                .addKeyValue("error", ex.getMessage())
                .log();
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

    /**
     * Anything nobody planned for. The details go to the log, with the request id; the caller
     * gets a translated sentence and that same id, never a stack trace or an exception's text,
     * which can reveal how the application is built.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, WebRequest request, Locale locale)
            throws Exception {
        if (request instanceof ServletWebRequest servlet && servlet.getResponse() != null
                && servlet.getResponse().isCommitted()) {
            // Half a response has already gone out (a photo being streamed when the client left):
            // there is no way to send an error now, so let the container deal with it.
            throw ex;
        }
        String requestId = MDC.get(RequestIdFilter.MDC_REQUEST_ID);
        log.atError().setMessage("request.failed")
                .addKeyValue("error", ex.getClass().getName())
                .setCause(ex)
                .log();
        ProblemDetail problem = problem(HttpStatus.INTERNAL_SERVER_ERROR,
                new DefaultMessageSourceResolvable(new String[] {UNEXPECTED}, new Object[] {requestId}),
                UNEXPECTED, locale);
        problem.setProperty("requestId", requestId);
        return ResponseEntity.internalServerError().body(problem);
    }

    /**
     * Spring MVC's own errors (a missing header, a malformed parameter, unreadable JSON, a file
     * over the upload limit) arrive here with their detail already translated from the
     * {@code problemDetail.*} keys; this gives them the same translated title as every other
     * error, so a client never sees an English "Bad Request" next to a Hindi sentence.
     */
    @Override
    protected ResponseEntity<Object> createResponseEntity(Object body, HttpHeaders headers,
                                                          HttpStatusCode statusCode, WebRequest request) {
        // The last step, where the body is final: for most of Spring's errors it is only built
        // (from the problemDetail.* keys) just before this.
        if (body instanceof ProblemDetail problem && statusCode instanceof HttpStatus status) {
            problem.setTitle(title(status, LocaleContextHolder.getLocale()));
        }
        return super.createResponseEntity(body, headers, statusCode, request);
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
