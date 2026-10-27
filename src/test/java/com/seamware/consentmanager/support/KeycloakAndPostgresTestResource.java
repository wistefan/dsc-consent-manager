package com.seamware.consentmanager.support;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Base for integration tests needing a real database and a real identity provider. Merges in {@link
 * KeycloakTestResource#keycloakProperties()} and re-enables security.
 */
public abstract class KeycloakAndPostgresTestResource extends PostgresTestResource {

    private static final String SECURITY_ENABLED = "micronaut.security.enabled";

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = new LinkedHashMap<>(super.getProperties());
        properties.putAll(KeycloakTestResource.keycloakProperties());
        properties.put(SECURITY_ENABLED, "true");
        return properties;
    }
}
