package com.rentalhub.web.mvc;

import com.rentalhub.exception.InvalidRequestException;
import com.rentalhub.exception.LocalizedException;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.validation.BindingResult;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * A refused form, carried back to the page it came from with each problem beside its field.
 *
 * Bean validation (a blank title) is already on the form by the time a controller runs. A
 * business rule (a villa's plot too small, check-out before check-in) is found by the service
 * instead, and arrives as an exception with a message key and, often, a field. Registered on
 * the BindingResult under that field, it renders exactly like a validation error: next to the
 * input, in the page's language, from the same message files as the API's errors.
 *
 * A refused form is not shown in answer to the POST. It goes back by redirect, like a
 * successful one: the address bar then shows the page's own address, so reloading does not
 * post again, and the language menu and "sign in as" (which reload the current address) do
 * not land on a POST-only address.
 */
final class FormErrors {

    private FormErrors() {
    }

    static void reject(BindingResult errors, LocalizedException refused) {
        String field = refused instanceof InvalidRequestException invalid ? invalid.getField() : null;
        if (field != null && new BeanWrapperImpl(errors.getTarget()).isReadableProperty(field)) {
            errors.rejectValue(field, refused.getMessageKey(), refused.getArguments(), null);
        } else {
            errors.reject(refused.getMessageKey(), refused.getArguments(), null);
        }
    }

    /**
     * Keeps the form, as typed, and its errors for the next page, which finds them in its model
     * under the form's name — exactly where a form shown in answer to the POST would have them.
     */
    static void keepForNextPage(RedirectAttributes redirect, String formName, BindingResult errors) {
        redirect.addFlashAttribute(formName, errors.getTarget());
        redirect.addFlashAttribute(BindingResult.MODEL_KEY_PREFIX + formName, errors);
    }
}
