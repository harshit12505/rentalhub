package com.rentalhub.web.mvc;

import com.rentalhub.exception.LocalizedException;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The one-line messages shown at the top of the next page after a form is posted: "Booked!",
 * "Photo added", or what went wrong.
 *
 * Every form on the site follows POST, then redirect, then GET, so reloading a page never
 * posts a form twice. A message has to survive that redirect; a "flash attribute" is exactly
 * that: kept in the session for one request, then gone. The text is resolved now, in the
 * language of the request that caused it.
 */
@Component
public class PageNotices {

    /** The model attribute the layout reads. */
    static final String NOTICE = "notice";

    private final MessageSource messages;

    public PageNotices(MessageSource messages) {
        this.messages = messages;
    }

    public void success(RedirectAttributes redirect, String key, Object... arguments) {
        add(redirect, "success", key, arguments);
    }

    public void info(RedirectAttributes redirect, String key, Object... arguments) {
        add(redirect, "info", key, arguments);
    }

    public void error(RedirectAttributes redirect, String key, Object... arguments) {
        add(redirect, "danger", key, arguments);
    }

    /** A refusal from a service, in the words its message key gives it. */
    public void error(RedirectAttributes redirect, LocalizedException refused) {
        redirect.addFlashAttribute(NOTICE,
                new Notice("danger", messages.getMessage(refused, LocaleContextHolder.getLocale())));
    }

    private void add(RedirectAttributes redirect, String kind, String key, Object... arguments) {
        redirect.addFlashAttribute(NOTICE,
                new Notice(kind, messages.getMessage(key, arguments, LocaleContextHolder.getLocale())));
    }

    /**
     * @param kind a Bootstrap alert colour: success, info, warning or danger
     * @param text the message, already in the right language
     */
    public record Notice(String kind, String text) {
    }
}
