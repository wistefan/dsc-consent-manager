package com.seamware.consentmanager.notification;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.env.Environment;
import java.util.LinkedHashMap;
import java.util.Map;

/** Starts a context on the real application.yml, without a database and blind to the shell. */
final class NotificationContexts {

    static final String CONFIGURED_HOST = "localhost";
    static final String FROM_ADDRESS = "noreply@example.org";

    private NotificationContexts() {}

    /**
     * Ignores exported variables, so an {@code SMTP_HOST} in the developer's shell changes nothing.
     */
    static ApplicationContext start(Map<String, Object> overrides) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("datasources.default.enabled", false);
        properties.put("flyway.enabled", false);
        properties.putAll(overrides);
        return ApplicationContext.builder(Environment.TEST)
                .environmentPropertySource(false)
                .properties(properties)
                .start();
    }

    /** SMTP configured with a sender, plus the given extra settings. */
    static Map<String, Object> smtp(Map<String, Object> extra) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("smtp.host", CONFIGURED_HOST);
        properties.put("smtp.from-address", FROM_ADDRESS);
        properties.putAll(extra);
        return properties;
    }
}
