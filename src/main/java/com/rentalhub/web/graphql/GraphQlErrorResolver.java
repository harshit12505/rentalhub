package com.rentalhub.web.graphql;

import com.rentalhub.exception.ConflictException;
import com.rentalhub.exception.ImageStorageUnavailableException;
import com.rentalhub.exception.InvalidRequestException;
import com.rentalhub.exception.LocalizedException;
import com.rentalhub.exception.OperationNotAllowedException;
import com.rentalhub.exception.PaymentFailedException;
import com.rentalhub.exception.PaymentUnavailableException;
import com.rentalhub.exception.ResourceNotFoundException;
import graphql.ErrorClassification;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * GraphQL's equivalent of GlobalExceptionHandler: the same exceptions become GraphQL errors
 * with the same translated sentence and the same {@code messageKey}.
 *
 * GraphQL answers 200 even when a field fails, and reports failures in an {@code errors} list
 * beside the data, each with a {@code classification} (BAD_REQUEST, NOT_FOUND, FORBIDDEN...)
 * instead of an HTTP status. Three situations have no standard classification, so this adds
 * its own: CONFLICT, PAYMENT_FAILED and UNAVAILABLE, matching REST's 409, 402 and 503.
 *
 * An exception nobody planned for is logged and answered with a generic translated sentence:
 * a GraphQL error message goes straight to the client, and an exception's own text can reveal
 * how the application is built.
 */
@Slf4j
@Component
class GraphQlErrorResolver extends DataFetcherExceptionResolverAdapter {

    /** The classifications GraphQL's ErrorType lacks. */
    enum RentalHubErrorType implements ErrorClassification {
        CONFLICT, PAYMENT_FAILED, UNAVAILABLE
    }

    private final MessageSource messages;

    GraphQlErrorResolver(MessageSource messages) {
        this.messages = messages;
    }

    @Override
    protected GraphQLError resolveToSingleError(Throwable ex, DataFetchingEnvironment env) {
        Locale locale = env.getLocale() == null ? Locale.ENGLISH : env.getLocale();
        if (ex instanceof LocalizedException localized) {
            Map<String, Object> extensions = new LinkedHashMap<>();
            extensions.put("messageKey", localized.getMessageKey());
            if (localized instanceof InvalidRequestException invalid && invalid.getField() != null) {
                extensions.put("field", invalid.getField());
            }
            return GraphqlErrorBuilder.newError(env)
                    .errorType(classify(localized))
                    .message(messages.getMessage(localized, locale))
                    .extensions(extensions)
                    .build();
        }
        if (ex instanceof ConstraintViolationException invalid) {
            return GraphqlErrorBuilder.newError(env)
                    .errorType(ErrorType.BAD_REQUEST)
                    .message(messages.getMessage("error.validation.detail", null, locale))
                    .extensions(Map.of("errors", fieldErrors(invalid)))
                    .build();
        }
        if (ex instanceof ConcurrencyFailureException) {
            return GraphqlErrorBuilder.newError(env)
                    .errorType(RentalHubErrorType.CONFLICT)
                    .message(messages.getMessage("error.concurrentUpdate", null, locale))
                    .extensions(Map.of("messageKey", "error.concurrentUpdate"))
                    .build();
        }
        log.atError().setMessage("graphql.failed")
                .addKeyValue("field", env.getField().getName())
                .addKeyValue("error", ex.getClass().getName())
                .setCause(ex)
                .log();
        return GraphqlErrorBuilder.newError(env)
                .errorType(ErrorType.INTERNAL_ERROR)
                .message(messages.getMessage("error.unexpected", new Object[] {"-"}, locale))
                .extensions(Map.of("messageKey", "error.unexpected"))
                .build();
    }

    private static ErrorClassification classify(LocalizedException ex) {
        return switch (ex) {
            case InvalidRequestException invalid -> ErrorType.BAD_REQUEST;
            case ResourceNotFoundException missing -> ErrorType.NOT_FOUND;
            case OperationNotAllowedException forbidden -> ErrorType.FORBIDDEN;
            case ConflictException conflict -> RentalHubErrorType.CONFLICT;
            case PaymentFailedException declined -> RentalHubErrorType.PAYMENT_FAILED;
            case PaymentUnavailableException unavailable -> RentalHubErrorType.UNAVAILABLE;
            case ImageStorageUnavailableException unavailable -> RentalHubErrorType.UNAVAILABLE;
            default -> ErrorType.INTERNAL_ERROR;
        };
    }

    /**
     * One entry per broken rule, named by the input field ("paymentMethodId"), not by the
     * validator's full path ("createBooking.input.paymentMethodId"). The messages were already
     * rendered in the caller's language by the validator.
     */
    private static List<Map<String, String>> fieldErrors(ConstraintViolationException invalid) {
        return invalid.getConstraintViolations().stream()
                .map(violation -> Map.of("field", lastNode(violation), "message", violation.getMessage()))
                .sorted(Comparator.comparing(error -> error.get("field")))
                .toList();
    }

    private static String lastNode(ConstraintViolation<?> violation) {
        String name = "";
        for (Path.Node node : violation.getPropertyPath()) {
            name = node.getName();
        }
        return name;
    }
}
