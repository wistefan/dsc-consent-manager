package com.seamware.consentmanager.support;

import com.seamware.consentmanager.config.ConsentManagerConfiguration;
import com.seamware.consentmanager.observability.LogPseudonymizer;

/** Builds a {@link LogPseudonymizer} outside a context, for unit tests of its callers. */
public final class TestPseudonymizer {

    /** A fixed, non-secret key long enough to pass the minimum. */
    public static final String KEY = "unit-test-log-pseudonym-key-not-a-secret";

    private TestPseudonymizer() {}

    /** A pseudonymizer over {@link #KEY}. */
    public static LogPseudonymizer create() {
        return withKey(KEY);
    }

    /** A pseudonymizer over {@code key}; {@code null} stands for an unset one. */
    public static LogPseudonymizer withKey(String key) {
        ConsentManagerConfiguration.Logging configuration =
                new ConsentManagerConfiguration.Logging();
        configuration.setPseudonymKey(key);
        return new LogPseudonymizer(configuration);
    }
}
