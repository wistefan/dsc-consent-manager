package com.seamware.consentmanager.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jwt.SignedJWT;
import io.micronaut.security.token.jwt.signature.SignatureConfiguration;
import jakarta.inject.Singleton;

/**
 * A {@link SignatureConfiguration} that accepts nothing, registered solely so that the set of
 * signature configurations is never empty.
 *
 * <p>This bean exists to close an authentication bypass rather than to verify anything. Micronaut's
 * {@code AbstractJsonWebTokenValidator.validateSignature(PlainJWT)} short-circuits when no {@link
 * SignatureConfiguration} bean is present:
 *
 * <pre>{@code
 * if (signatureConfigurations.isEmpty()) {
 *     return true;   // "nothing to check against" is treated as "valid"
 * }
 * }</pre>
 *
 * <p>An unsigned {@code alg: none} token therefore <strong>authenticates</strong> whenever the
 * collection is empty. That is not a hypothetical: the OpenID Connect clients configured under
 * {@code micronaut.security.oauth2.clients} only contribute their JWKS-backed key material once
 * discovery has succeeded, so the collection is empty during startup, throughout an identity
 * provider outage, and permanently if an issuer is misconfigured. Without this bean an IdP outage
 * would silently degrade into "every forged token is accepted" — the exact inverse of the
 * requirement that unsigned and symmetrically signed tokens be rejected.
 *
 * <p>Registering one configuration that matches no algorithm and verifies no token makes the
 * collection permanently non-empty, so the short-circuit above can never be reached. Because {@link
 * #supports(JWSAlgorithm)} returns {@code false} for every algorithm, this bean never participates
 * in the validation of a genuine token: a correctly signed token is still verified by the JWKS
 * configuration its issuer contributes.
 *
 * <p>The guarantee is "fail closed": with no usable signing key, every token is rejected.
 *
 * @see IdentityProviderRegistryValidator
 */
@Singleton
public class UnsignedTokenRejector implements SignatureConfiguration {

    /**
     * Describes the algorithms this configuration accepts, for diagnostic messages.
     *
     * @return a description stating that no algorithm is accepted
     */
    @Override
    public String supportedAlgorithmsMessage() {
        return "no algorithm (placeholder that rejects every token)";
    }

    /**
     * Reports whether this configuration can verify the given algorithm.
     *
     * @param algorithm the algorithm named in the token header
     * @return always {@code false}, so this bean never verifies a genuine token
     */
    @Override
    public boolean supports(JWSAlgorithm algorithm) {
        return false;
    }

    /**
     * Verifies a signed token.
     *
     * @param jwt the parsed token
     * @return always {@code false}; this configuration accepts no token
     */
    @Override
    public boolean verify(SignedJWT jwt) {
        return false;
    }
}
