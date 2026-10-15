package com.seamware.consentmanager.signing;

import com.nimbusds.jose.jwk.JWK;
import com.seamware.consentmanager.config.ConsentManagerConfiguration;
import com.seamware.consentmanager.support.TestJson;

/** Builds the signing beans by hand, configured as the receipt settings would bind them. */
final class SigningFixtures {

    private SigningFixtures() {}

    /** A store over {@code configured}, or over an ephemeral key when it is null. */
    static SigningKeyStore store(JWK configured, String retired) {
        ConsentManagerConfiguration.Receipts settings = new ConsentManagerConfiguration.Receipts();
        settings.setRetiredVerificationKeys(retired);
        if (configured == null) {
            settings.setEphemeralSigningKey(true);
        } else {
            settings.setSigningKey(configured.toJSONString());
        }
        return new SigningKeyStore(settings);
    }

    static SigningKeyStore store(JWK configured) {
        return store(configured, null);
    }

    static CanonicalJsonSerializer canonical() {
        return new CanonicalJsonSerializer(TestJson.mapper());
    }

    static JwsConsentSigningService signing(SigningKeyStore keys) {
        return new JwsConsentSigningService(keys, canonical());
    }
}
