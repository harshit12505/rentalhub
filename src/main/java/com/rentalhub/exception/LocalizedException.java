package com.rentalhub.exception;

import org.springframework.context.MessageSourceResolvable;

/**
 * Base class for every exception whose message a user may end up reading.
 *
 * It carries a message key plus arguments instead of an English sentence, and
 * implements {@link MessageSourceResolvable}, so any {@code MessageSource} can turn
 * it into text in the caller's language with {@code messageSource.getMessage(ex, locale)}.
 * {@link #getMessage()} returns the key, which is what belongs in server logs.
 *
 * Arguments may themselves be {@code MessageSourceResolvable} (for example a field
 * label); Spring resolves those too, so "{0} is required" can read "Plot area is
 * required" in English and the equivalent in Hindi.
 */
public abstract class LocalizedException extends RuntimeException implements MessageSourceResolvable {

    private final String messageKey;
    private final transient Object[] arguments;

    protected LocalizedException(String messageKey, Object... arguments) {
        super(messageKey);
        this.messageKey = messageKey;
        this.arguments = arguments == null ? new Object[0] : arguments.clone();
    }

    public String getMessageKey() {
        return messageKey;
    }

    @Override
    public String[] getCodes() {
        return new String[] {messageKey};
    }

    @Override
    public Object[] getArguments() {
        return arguments.clone();
    }

    /** No English fallback on purpose: a missing translation should fail loudly in tests. */
    @Override
    public String getDefaultMessage() {
        return null;
    }
}
