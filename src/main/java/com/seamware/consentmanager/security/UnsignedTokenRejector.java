package com.seamware.consentmanager.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jwt.SignedJWT;
import io.micronaut.context.annotation.Requires;
import io.micronaut.security.token.jwt.signature.SignatureConfiguration;
import jakarta.inject.Singleton;

/**
 * A {@link SignatureConfiguration} that accepts nothing, registered so the set is never empty.
 *
 * <p>Security invariant: micronaut-security's {@code AbstractJsonWebTokenValidator} treats an
 * unsigned {@code alg: none} token as valid when no signature configuration exists at all, as
 * during startup or an identity-provider outage. This bean keeps that set non-empty so the service
 * fails closed, yet never verifies a genuine token. {@link #ENABLED_PROPERTY} may be {@code false}
 * only under the {@code test} environment; {@link IdentityProviderRegistryValidator} enforces that.
 * See ADR-0002.
 */
@Requires(
        property = UnsignedTokenRejector.ENABLED_PROPERTY,
        notEquals = "false",
        defaultValue = "true")
@Singleton
public class UnsignedTokenRejector implements SignatureConfiguration {

    /**
     * Disables this bean, re-opening the {@code alg: none} bypass; exists only for a control test.
     * Setting it {@code false} outside the {@code test} environment fails startup.
     */
    public static final String ENABLED_PROPERTY =
            "consent-manager.security.unsigned-token-rejector.enabled";

    /** States, for diagnostics, that no algorithm is accepted. */
    @Override
    public String supportedAlgorithmsMessage() {
        return "no algorithm (placeholder that rejects every token)";
    }

    /** Always {@code false}, so this bean never verifies a genuine token. */
    @Override
    public boolean supports(JWSAlgorithm algorithm) {
        return false;
    }

    /** Always {@code false}; this configuration accepts no token. */
    @Override
    public boolean verify(SignedJWT jwt) {
        return false;
    }
}
