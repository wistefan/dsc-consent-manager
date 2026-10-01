package com.seamware.consentmanager.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jwt.SignedJWT;
import io.micronaut.context.annotation.Requires;
import io.micronaut.security.token.jwt.signature.SignatureConfiguration;
import jakarta.inject.Singleton;

/**
 * A {@link SignatureConfiguration} that accepts nothing, registered solely so that the set of
 * signature configurations is never empty.
 *
 * <p>This bean exists to close an authentication bypass rather than to verify anything. Micronaut's
 * {@code AbstractJsonWebTokenValidator} latches a flag in its constructor (verified against
 * micronaut-security 5.4.0):
 *
 * <pre>{@code
 * this.noSignatures = imperativeSignatureConfigurations.isEmpty()
 *         && reactiveSignatureConfigurations.isEmpty();
 * }</pre>
 *
 * <p>and treats a {@code PlainJWT} as validly signed whenever that flag is set — "nothing to check
 * against" is read as "valid". The flag is a single {@code AND} over <em>both</em> collections and
 * is shared by the imperative and the reactive validator, so one bean in either collection closes
 * both paths.
 *
 * <p>An unsigned {@code alg: none} token therefore <strong>authenticates</strong> whenever both
 * collections are empty. That is not a hypothetical: the OpenID Connect clients configured under
 * {@code micronaut.security.oauth2.clients} only contribute their JWKS-backed key material once
 * discovery has succeeded, so both collections are empty during startup, throughout an identity
 * provider outage, and permanently if an issuer is misconfigured. Without this bean an IdP outage
 * would silently degrade into "every forged token is accepted" — the exact inverse of the
 * requirement that unsigned and symmetrically signed tokens be rejected.
 *
 * <p>Registering one configuration that matches no algorithm and verifies no token makes the
 * imperative collection permanently non-empty, so the short-circuit above can never be reached.
 * Because {@link #supports(JWSAlgorithm)} returns {@code false} for every algorithm, this bean
 * never participates in the validation of a genuine token: a correctly signed token is still
 * verified by the key set its issuer contributes.
 *
 * <p>Note that the two collections hold different beans, which is why this one is not made
 * redundant by a provider resolving successfully. The JWKS bean an OpenID client contributes is
 * {@code ReactiveJwksSignature}, declared {@code @EachBean(JwksSignatureConfiguration.class)} and
 * implementing {@code ReactiveSignatureConfiguration<SignedJWT>} — <em>not</em> {@link
 * SignatureConfiguration}. The imperative collection this bean joins is otherwise empty even when
 * discovery has succeeded.
 *
 * <p>The guarantee is "fail closed": with no usable signing key, every token is rejected.
 *
 * <p>The bean can be switched off with {@link #ENABLED_PROPERTY}. That exists purely so a test can
 * prove the bean is load-bearing: every assertion about this class is an assertion that a token is
 * <em>rejected</em>, and a token is rejected for many reasons, so such an assertion would stay
 * green if the bean were deleted as apparently dead code ({@link #supports(JWSAlgorithm)} returns
 * {@code false} for every algorithm and {@link #verify(SignedJWT)} always {@code false}). The
 * control case disables it and requires the same unsigned token to be <em>accepted</em>, which
 * fails if the bean ever stops closing the bypass.
 *
 * <p>Because that switch would otherwise put an authentication bypass one environment variable away
 * from any deployment, it is <strong>not usable outside a test context</strong>: {@link
 * IdentityProviderRegistryValidator#validate()} aborts startup when the property is {@code false}
 * and {@code test} is not among the active environment names. The control case keeps working, since
 * it builds its own context with the {@code test} environment active, while a deployment that sets
 * the property fails fast rather than failing open.
 *
 * <p>See {@code docs/adr/0001-delegate-oidc-discovery-and-jwks-to-micronaut-security.md} for why
 * signature verification is delegated to Micronaut at all, and what else that delegation leaves to
 * this service.
 *
 * @see IdentityProviderRegistryValidator
 */
@Requires(
        property = UnsignedTokenRejector.ENABLED_PROPERTY,
        notEquals = "false",
        defaultValue = "true")
@Singleton
public class UnsignedTokenRejector implements SignatureConfiguration {

    /**
     * Property that disables this bean, re-opening the {@code alg: none} bypass.
     *
     * <p>Present only so the control case in {@code TokenSignatureEnforcementIT} can demonstrate
     * that the bypass is real and that this bean is what closes it. Setting it to {@code false}
     * outside a context with the {@code test} environment active is a startup failure, enforced by
     * {@link IdentityProviderRegistryValidator#validate()}.
     */
    public static final String ENABLED_PROPERTY =
            "consent-manager.security.unsigned-token-rejector.enabled";

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
