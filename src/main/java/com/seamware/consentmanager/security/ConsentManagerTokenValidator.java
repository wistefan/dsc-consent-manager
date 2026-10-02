package com.seamware.consentmanager.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.async.annotation.SingleResult;
import io.micronaut.http.HttpRequest;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.token.validator.TokenValidator;
import jakarta.inject.Singleton;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Turns a bearer token into an {@link Authentication}, or into nothing at all.
 *
 * <p>A Micronaut Security {@link TokenValidator}: {@code TokenAuthenticationFetcher} hands it every
 * bearer token, and an empty publisher - the one and only failure signal - renders as {@code 401}.
 * No rejection says why and none echoes the submitted issuer back, so the trust list cannot be
 * enumerated through the response (US-ID-008); the issuer is logged at {@code DEBUG} only.
 *
 * <p>The pipeline, in order: parse without trusting anything; refuse any algorithm outside {@link
 * #PERMITTED_SIGNATURE_ALGORITHMS}; resolve {@code iss} through {@link
 * IdentityProviderRegistry#findByIssuer(String)}, which answers for resolved trust-list entries
 * only; verify the signature through {@link IssuerSignatureVerifier} against that issuer's keys
 * alone; then, on claims that signature now covers, check {@code exp}/{@code iat}/{@code nbf} under
 * the provider's clock skew, check {@code aud}, require the non-blank {@code sub} the token
 * contract mandates and which becomes the authentication's name, map roles, and require the
 * identifier those roles imply.
 *
 * <p>The algorithm check precedes key lookup deliberately: a token signed {@code HS256} with a
 * provider's published RSA modulus as the HMAC secret is refused for its {@code alg}, whatever the
 * key-selection layer would have done with it.
 *
 * <p>Why this class exists instead of the module's own JWT validator, and which module components
 * it delegates to, are recorded in {@code
 * docs/adr/0005-own-token-validator-on-micronaut-security-jwt.md}. The module's validators are
 * switched off in {@code application.yml} for the reasons given there.
 *
 * @see ClaimMapper
 * @see IssuerSignatureVerifier
 * @see IdentityProviderRegistry
 */
@Singleton
public class ConsentManagerTokenValidator implements TokenValidator<HttpRequest<?>> {

    /**
     * The signature algorithms a token may be signed with: asymmetric only, and exactly the set the
     * token contract in {@code api/components/security.yaml} publishes. Symmetric algorithms are
     * absent because a shared secret would let this resource server mint the tokens it validates.
     */
    public static final Set<JWSAlgorithm> PERMITTED_SIGNATURE_ALGORITHMS =
            Set.of(
                    JWSAlgorithm.RS256,
                    JWSAlgorithm.RS384,
                    JWSAlgorithm.RS512,
                    JWSAlgorithm.PS256,
                    JWSAlgorithm.PS384,
                    JWSAlgorithm.PS512,
                    JWSAlgorithm.ES256,
                    JWSAlgorithm.ES384,
                    JWSAlgorithm.ES512);

    /**
     * Attribute under which the {@link Authentication} carries the verified issuer, which is the
     * key {@link IdentityProviderRegistry#findByIssuer(String)} takes: principal resolution reaches
     * the provider's configured claim names through it without repeating the pipeline's work.
     *
     * <p>The provider's configuration <em>name</em> is deliberately not what is carried here - the
     * registry offers no lookup by it. Namespaced, and written after the raw claims, so a token
     * carrying a claim of this name cannot supply the value.
     */
    public static final String IDENTITY_PROVIDER_ISSUER_ATTRIBUTE =
            "consent-manager.identity-provider-issuer";

    /**
     * Counter of tokens refused for their claims after their signature verified, tagged by provider
     * and by {@link #REASON_TAG}.
     *
     * <p>Every such refusal renders as the same bare {@code 401}, so without this an operator
     * cannot tell the commonest integration mistake - an {@code audience} that does not match what
     * the provider mints - from a forged-token flood. The counterpart for signature failures is
     * {@link IssuerSignatureVerifier#SIGNATURE_VERIFICATION_METRIC}, and the tag naming is shared
     * with it. Server-side telemetry only: the response stays generic (US-ID-008).
     */
    public static final String CLAIM_REJECTION_METRIC = "consentmanager.token.claim.rejections";

    /** Tag carrying the coarse reason a token's claims were refused. */
    public static final String REASON_TAG = "reason";

    /** Value of {@link #REASON_TAG} for a token whose {@code aud} omits the configured audience. */
    public static final String REASON_AUDIENCE = "audience";

    /**
     * Value of {@link #REASON_TAG} for a token outside its validity window under the clock skew.
     */
    public static final String REASON_TIME = "time";

    /** Value of {@link #REASON_TAG} for a token carrying no non-blank {@code sub}. */
    public static final String REASON_SUBJECT = "subject";

    /** Value of {@link #REASON_TAG} for a token missing the identifier claim its roles imply. */
    public static final String REASON_IDENTIFIER = "identifier";

    private static final Logger LOG = LoggerFactory.getLogger(ConsentManagerTokenValidator.class);

    private final IdentityProviderRegistry registry;
    private final IssuerSignatureVerifier signatureVerifier;
    private final ClaimMapper claimMapper;
    private final MeterRegistry meterRegistry;

    /**
     * Creates the validator.
     *
     * @param registry the trust list, consulted to resolve a token's issuer
     * @param signatureVerifier the per-issuer signature check
     * @param claimMapper reads claims by the names the issuing provider was configured with
     * @param meterRegistry where {@link #CLAIM_REJECTION_METRIC} is counted; a throwaway registry
     *     is used when metrics are switched off, so the counting code has no second path
     */
    public ConsentManagerTokenValidator(
            IdentityProviderRegistry registry,
            IssuerSignatureVerifier signatureVerifier,
            ClaimMapper claimMapper,
            @Nullable MeterRegistry meterRegistry) {
        this.registry = registry;
        this.signatureVerifier = signatureVerifier;
        this.claimMapper = claimMapper;
        this.meterRegistry = meterRegistry == null ? new SimpleMeterRegistry() : meterRegistry;
    }

    /**
     * Validates a bearer token and, if it holds up, describes who presented it. Non-blocking: the
     * signature check may have to fetch a key set.
     *
     * @param token the raw token taken from the {@code Authorization} header
     * @param request the request it arrived on; unused, and {@code null} outside a request
     * @return a publisher emitting one {@link Authentication}, or nothing if the token is invalid
     *     for any reason whatsoever
     */
    @Override
    @SingleResult
    public Publisher<Authentication> validateToken(String token, @Nullable HttpRequest<?> request) {
        SignedJWT jwt = parse(token);
        if (jwt == null) {
            return Mono.empty();
        }
        JWSAlgorithm algorithm = jwt.getHeader().getAlgorithm();
        if (!PERMITTED_SIGNATURE_ALGORITHMS.contains(algorithm)) {
            LOG.debug("A token was presented with the unaccepted algorithm '{}'", algorithm);
            return Mono.empty();
        }
        JWTClaimsSet claims = claimsOf(jwt);
        if (claims == null) {
            return Mono.empty();
        }
        String issuer = claims.getIssuer();
        Optional<ResolvedIdentityProvider> found = registry.findByIssuer(issuer);
        if (found.isEmpty()) {
            LOG.debug("A token was presented for the unusable or unknown issuer '{}'", issuer);
            return Mono.empty();
        }
        ResolvedIdentityProvider provider = found.get();
        return Mono.from(signatureVerifier.verify(issuer, jwt))
                .filter(Boolean::booleanValue)
                .flatMap(verified -> Mono.justOrEmpty(authenticate(provider, claims)));
    }

    /**
     * Applies the checks that only make sense once the signature has verified, and builds the
     * {@link Authentication} if they all pass.
     *
     * @param provider the trust-list entry whose keys signed the token
     * @param claims the claims that signature covers
     * @return the authenticated caller, or empty if the token fails any remaining check
     */
    private Optional<Authentication> authenticate(
            ResolvedIdentityProvider provider, JWTClaimsSet claims) {
        IdentityProviderConfiguration configuration = provider.configuration();
        if (!hasValidTimeClaims(claims, configuration.getClockSkew())) {
            return reject(provider, REASON_TIME);
        }
        if (!claims.getAudience().contains(configuration.getAudience())) {
            LOG.debug("A token from provider '{}' names another audience", provider.name());
            return reject(provider, REASON_AUDIENCE);
        }
        String subject = subjectOf(claims, provider);
        if (subject == null) {
            return reject(provider, REASON_SUBJECT);
        }
        Set<Role> roles = claimMapper.mapRoles(claims, configuration);
        if (!hasRequiredIdentifier(claims, configuration, roles)) {
            return reject(provider, REASON_IDENTIFIER);
        }
        Map<String, Object> attributes = new LinkedHashMap<>(claims.getClaims());
        attributes.put(IDENTITY_PROVIDER_ISSUER_ATTRIBUTE, provider.issuer());
        List<String> authorities = roles.stream().map(Role::name).toList();
        return Optional.of(Authentication.build(subject, authorities, attributes));
    }

    /**
     * Counts a claim-level refusal and yields the empty result that renders as {@code 401}.
     *
     * @param provider the trust-list entry whose keys signed the token
     * @param reason one of {@link #REASON_TIME}, {@link #REASON_AUDIENCE}, {@link #REASON_SUBJECT}
     *     or {@link #REASON_IDENTIFIER}
     * @return always empty
     */
    private Optional<Authentication> reject(ResolvedIdentityProvider provider, String reason) {
        Counter.builder(CLAIM_REJECTION_METRIC)
                .tag(IssuerSignatureVerifier.PROVIDER_TAG, provider.name())
                .tag(REASON_TAG, reason)
                .register(meterRegistry)
                .increment();
        return Optional.empty();
    }

    /**
     * Reads the {@code sub} the token contract requires, which becomes {@link
     * Authentication#getName()}.
     *
     * <p><strong>{@code sub} is unique per issuer only</strong>, so with more than one provider in
     * the trust list two callers can share a name. It is a display and log value; anything that
     * keys on a caller - audit records, rate limiting, ownership - must use the globally unique
     * identifier claim the typed principals carry, or pair the subject with {@link
     * #IDENTITY_PROVIDER_ISSUER_ATTRIBUTE}.
     *
     * @param claims the claims to read
     * @param provider the issuing provider, for the log line only
     * @return the subject, or {@code null} if the token carries none
     */
    @Nullable
    private static String subjectOf(JWTClaimsSet claims, ResolvedIdentityProvider provider) {
        String subject = claims.getSubject();
        if (subject == null || subject.isBlank()) {
            LOG.debug("A token from provider '{}' carries no subject", provider.name());
            return null;
        }
        return subject;
    }

    /**
     * Checks the time claims against the provider's own {@code clock-skew}, which is the tolerance
     * between this service's clock and that provider's.
     *
     * <p>{@code exp} is required: a bearer token without an expiry never stops being valid. {@code
     * iat} and {@code nbf} are honoured when present and tolerated when absent, RFC 7519 making
     * both optional - the published contract asks providers for {@code iat} all the same, being
     * stricter than what is enforced costing nothing.
     *
     * @param claims the claims to check
     * @param clockSkew the provider's configured tolerance
     * @return {@code true} if the token is currently within its validity window
     */
    private static boolean hasValidTimeClaims(JWTClaimsSet claims, Duration clockSkew) {
        Instant now = Instant.now();
        Instant expiry = toInstant(claims.getExpirationTime());
        if (expiry == null) {
            LOG.debug("A token carries no exp claim");
            return false;
        }
        if (expiry.plus(clockSkew).isBefore(now)) {
            LOG.debug("A token expired more than the configured clock skew ago");
            return false;
        }
        Instant issuedAt = toInstant(claims.getIssueTime());
        if (issuedAt != null && issuedAt.minus(clockSkew).isAfter(now)) {
            LOG.debug("A token claims to have been issued in the future");
            return false;
        }
        Instant notBefore = toInstant(claims.getNotBeforeTime());
        if (notBefore != null && notBefore.minus(clockSkew).isAfter(now)) {
            LOG.debug("A token is not valid yet");
            return false;
        }
        return true;
    }

    /**
     * Requires the identifier claim the token's mapped roles imply: a {@link Role#USER} token must
     * name its user and a {@link Role#PARTICIPANT} token its participant, because downstream
     * authorization is made against that identifier. {@link Role#CATALOG} acts for the dataspace
     * and is identified by issuer and subject alone; a token with no mapped role has no
     * role-specific identifier to demand and is refused later by {@code @Secured} with {@code 403}.
     *
     * @param claims the claims to read
     * @param configuration the issuing provider's configuration, naming the identifier claims
     * @param roles the roles already mapped from the token
     * @return {@code true} if every identifier the roles require is present
     */
    private boolean hasRequiredIdentifier(
            JWTClaimsSet claims, IdentityProviderConfiguration configuration, Set<Role> roles) {
        IdentityProviderConfiguration.ClaimsConfiguration claimNames = configuration.getClaims();
        if (roles.contains(Role.USER)
                && claimMapper.findString(claims, claimNames.getUserIdentifier()).isEmpty()) {
            LOG.debug("A USER token carries no user identifier claim");
            return false;
        }
        if (roles.contains(Role.PARTICIPANT)
                && claimMapper
                        .findString(claims, claimNames.getParticipantIdentifier())
                        .isEmpty()) {
            LOG.debug("A PARTICIPANT token carries no participant identifier claim");
            return false;
        }
        return true;
    }

    /**
     * Decodes a token far enough to read it, without believing any of it. An {@code alg: none}
     * token does not parse as a {@link SignedJWT} and is refused here, before the allow-list.
     *
     * @param token the raw compact serialisation; may be {@code null} or malformed
     * @return the parsed token, or {@code null} if it is not a well-formed signed JWT
     */
    @Nullable
    private static SignedJWT parse(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            return SignedJWT.parse(token);
        } catch (ParseException e) {
            LOG.debug("A bearer token that is not a well-formed signed JWT was presented", e);
            return null;
        }
    }

    /**
     * Reads a parsed token's claim set.
     *
     * @param jwt the parsed token
     * @return the claims, or {@code null} if the payload is not a JSON object of claims
     */
    @Nullable
    private static JWTClaimsSet claimsOf(SignedJWT jwt) {
        try {
            return jwt.getJWTClaimsSet();
        } catch (ParseException e) {
            LOG.debug("A bearer token carried a payload that is not a claim set", e);
            return null;
        }
    }

    /**
     * Converts a Nimbus date claim to an {@link Instant}, tolerating its absence.
     *
     * @param claim the claim value, or {@code null} if the token does not carry it
     * @return the instant, or {@code null} if the claim is absent
     */
    @Nullable
    private static Instant toInstant(@Nullable Date claim) {
        return claim == null ? null : claim.toInstant();
    }
}
