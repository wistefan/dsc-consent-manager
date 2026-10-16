package com.seamware.consentmanager.notification;

import io.micronaut.context.condition.Condition;
import io.micronaut.context.condition.ConditionContext;

/** Exact complement of {@link SmtpConfiguredCondition}; admits the logging implementation. */
public class SmtpNotConfiguredCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context) {
        return !SmtpHost.isConfigured(context);
    }
}
