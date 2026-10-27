package com.seamware.consentmanager.notification;

import io.micronaut.context.condition.ConditionContext;

/** The one predicate both SMTP conditions share, so they cannot drift out of being complements. */
final class SmtpHost {

    /** Property whose non-blank value switches the SMTP implementation on. */
    static final String PROPERTY = "smtp.host";

    private SmtpHost() {}

    /** True when {@code smtp.host} is non-blank; an empty variable counts as unset. */
    static boolean isConfigured(ConditionContext<?> context) {
        return context.getProperty(PROPERTY, String.class)
                .filter(host -> !host.isBlank())
                .isPresent();
    }
}
