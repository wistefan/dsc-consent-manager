package com.seamware.consentmanager.notification;

import io.micronaut.context.condition.Condition;
import io.micronaut.context.condition.ConditionContext;

/** Admits the SMTP beans only when {@code smtp.host} is set and non-blank. */
public class SmtpConfiguredCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context) {
        return SmtpHost.isConfigured(context);
    }
}
