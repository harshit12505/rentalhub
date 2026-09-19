package com.rentalhub.web.mvc;

import com.rentalhub.exception.LocalizedException;
import com.rentalhub.exception.OperationNotAllowedException;
import com.rentalhub.exception.ResourceNotFoundException;
import com.rentalhub.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.ui.Model;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.support.RequestContextUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the web pages show when something goes wrong: an error page in the reader's language,
 * never a stack trace — the pages' counterpart of GlobalExceptionHandler, which only answers the
 * REST API (and in JSON).
 *
 * Refusals a form can explain are handled by the controllers themselves, next to the field. This
 * catches what is left: a listing that does not exist, an action this user may not take, a photo
 * too large to even read, and anything unexpected.
 */
@Slf4j
@ControllerAdvice(basePackageClasses = PageExceptionHandler.class)
public class PageExceptionHandler {

    private static final String TYPE_MISMATCH = "problemDetail.org.springframework.beans.TypeMismatchException";

    private static final Pattern PHOTO_UPLOAD = Pattern.compile("^(/listings/\\d+)/photos$");

    private final MessageSource messages;
    private final PageModelAdvice layout;

    public PageExceptionHandler(MessageSource messages, PageModelAdvice layout) {
        this.messages = messages;
        this.layout = layout;
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public String notFound(ResourceNotFoundException ex, HttpServletRequest request, HttpServletResponse response,
                           Model model) {
        return errorPage(HttpStatus.NOT_FOUND, messages.getMessage(ex, LocaleContextHolder.getLocale()),
                request, response, model);
    }

    @ExceptionHandler(OperationNotAllowedException.class)
    public String notAllowed(OperationNotAllowedException ex, HttpServletRequest request, HttpServletResponse response,
                             Model model) {
        return errorPage(HttpStatus.FORBIDDEN, messages.getMessage(ex, LocaleContextHolder.getLocale()),
                request, response, model);
    }

    /**
     * A photo over the size limit is refused before the controller runs, so there is no form to
     * put the message on. Go back to the listing it was meant for, with the message on top.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public String tooLarge(HttpServletRequest request, HttpServletResponse response, Model model) {
        Matcher upload = PHOTO_UPLOAD.matcher(request.getRequestURI());
        String text = messages.getMessage("problemDetail.org.springframework.web.multipart.MaxUploadSizeExceededException",
                null, LocaleContextHolder.getLocale());
        if (!upload.matches()) {
            return errorPage(HttpStatus.PAYLOAD_TOO_LARGE, text, request, response, model);
        }
        RequestContextUtils.getOutputFlashMap(request).put(PageNotices.NOTICE, new PageNotices.Notice("danger", text));
        return "redirect:" + upload.group(1) + "#photos";
    }

    /** Anything else a page did not expect: logged in full, shown as a sentence and the request id. */
    @ExceptionHandler(Exception.class)
    public String unexpected(Exception ex, HttpServletRequest request, HttpServletResponse response, Model model) {
        if (ex instanceof LocalizedException localized) {
            return errorPage(HttpStatus.BAD_REQUEST, messages.getMessage(localized, LocaleContextHolder.getLocale()),
                    request, response, model);
        }
        if (ex instanceof TypeMismatchException mismatch) {
            // /listings/abc, ?currency=XYZ: not an ErrorResponse, so given the key the API uses for it.
            return errorPage(HttpStatus.BAD_REQUEST, messages.getMessage(TYPE_MISMATCH,
                            new Object[] {mismatch.getPropertyName(), mismatch.getValue()}, LocaleContextHolder.getLocale()),
                    request, response, model);
        }
        if (ex instanceof ErrorResponse spring) {
            // Spring's own request errors (/listings/abc, a missing parameter): their status, and
            // the same translated problemDetail.* text the API gives.
            return errorPage(HttpStatus.valueOf(spring.getStatusCode().value()),
                    spring.updateAndGetBody(messages, LocaleContextHolder.getLocale()).getDetail(), request, response, model);
        }
        log.atError().setMessage("page.failed").addKeyValue("error", ex.getClass().getName()).setCause(ex).log();
        String requestId = MDC.get(RequestIdFilter.MDC_REQUEST_ID);
        return errorPage(HttpStatus.INTERNAL_SERVER_ERROR,
                messages.getMessage("error.unexpected", new Object[] {requestId}, LocaleContextHolder.getLocale()),
                request, response, model);
    }

    private String errorPage(HttpStatus status, String message, HttpServletRequest request, HttpServletResponse response,
                             Model model) {
        response.setStatus(status.value());
        model.addAllAttributes(layout.layoutForErrorPage(request));
        model.addAttribute("status", status.value());
        model.addAttribute("message", message);
        return "error";
    }
}
