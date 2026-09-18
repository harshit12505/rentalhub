package com.rentalhub.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.MessageSource;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Locale;

/**
 * The errors that happen before any controller is chosen: a path that exists but not for this
 * method (405), and a path that does not exist at all (404).
 *
 * GlobalExceptionHandler cannot see these, because it only handles errors from REST
 * controllers, and here there is no controller yet. This catches them for the API's own paths
 * only, so they get the same translated problem-detail shape as every other API error; for any
 * other path it steps aside (by rethrowing), and Spring Boot's ordinary error handling answers,
 * which is what the web pages of phase 8 will want.
 *
 * Ordered last, so that for errors inside a REST controller GlobalExceptionHandler always wins.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class ApiRoutingErrorHandler {

    private final MessageSource messages;

    public ApiRoutingErrorHandler(MessageSource messages) {
        this.messages = messages;
    }

    @ExceptionHandler({HttpRequestMethodNotSupportedException.class, NoResourceFoundException.class})
    public ResponseEntity<ProblemDetail> handleRoutingError(Exception ex, HttpServletRequest request, Locale locale)
            throws Exception {
        String path = request.getRequestURI();
        if (!(path.startsWith("/api/") || path.startsWith("/images/")) || !(ex instanceof ErrorResponse error)) {
            throw ex;
        }
        ProblemDetail problem = error.updateAndGetBody(messages, locale);
        int status = error.getStatusCode().value();
        problem.setTitle(messages.getMessage("error.title." + status, null, problem.getTitle(), locale));
        return ResponseEntity.status(error.getStatusCode()).headers(error.getHeaders()).body(problem);
    }
}
