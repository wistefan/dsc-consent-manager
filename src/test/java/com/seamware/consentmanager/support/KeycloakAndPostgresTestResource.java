package com.seamware.consentmanager.support;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Base class for integration tests needing both a real database and a real identity provider.
 *
 * <p>Java allows one superclass, so rather than extending both fixtures this one extends {@link
 * PostgresTestResource} and merges in {@link KeycloakTestResource#keycloakProperties()}. Security
 * is switched back on here because {@code application-test.yml} disables it for the suites that do
 * not want it.
 */
public abstract class KeycloakAndPostgresTestResource extends PostgresTestResource {

    /** Property re-enabling the security filter chain for this test's context. */
    private static final String SECURITY_ENABLED = "micronaut.security.enabled";

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = new LinkedHashMap<>(super.getProperties());
        properties.putAll(KeycloakTestResource.keycloakProperties());
        properties.put(SECURITY_ENABLED, "true");
        return properties;
    }
}
