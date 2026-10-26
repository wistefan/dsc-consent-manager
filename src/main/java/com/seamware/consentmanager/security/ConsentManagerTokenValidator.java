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
 * Turns a bearer token into an {@link Authentication}, or into nothing ({@code 401}). See ADR-0005.
 *
 * <p>Security invariant: the {@code alg} allow-list is checked before any key lookup (defeating
 * HS256-with-RSA-modulus confusion), the signature is verified against the token issuer's keys
 * alone, and only then are time, {@code aud}, {@code sub}, roles and identifier checked. No refusal
 * says why or echoes the issuer, so the trust list cannot be enumerated.
 */
@Singleton
public class ConsentManagerTokenValidator implements TokenValidator<HttpRequest<?>> {

    /**
     * Asymmetric only, as {@code security.yaml} publishes: a shared secret would let this service
     * mint the tokens it validates.
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
     * Authentication attribute carrying the verified issuer for principal resolution. Written after
     * the raw claims, so a token's own claim of this name cannot supply it.
     */
    public static final String IDENTITY_PROVIDER_ISSUER_ATTRIBUTE =
            "consent-manager.identity-provider-issuer";

    /**
     * Counts post-signature claim refusals by provider and {@link #REASON_TAG}, telling e.g. an
     * audience mismatch from a forged-token flood; the response itself stays a generic {@code 401}.
     */
    public static final String CLAIM_REJECTION_METRIC = "consentmanager.token.claim.rejections";

    /** Tag carrying the coarse reason a token's claims were refused. */
    public static final String REASON_TAG = "reason";

    /** Value of {@link #REASON_TAG} for a token whose {@code aud} omits the configured audience. */
    public static final String REASON_AUDIENCE = "audience";

    /** Value of {@link #REASON_TAG} for a token outside its validity window. */
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

    /** Creates the validator; a {@code null} meter registry is replaced by a throwaway one. */
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
     * Validates a bearer token, emitting one {@link Authentication} or nothing on any failure.
     * Non-blocking, since the signature check may fetch a key set.
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

    /** Applies the post-signature checks; empty if any fails. */
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

    /** Counts a claim-level refusal and returns empty, which renders as {@code 401}. */
    private Optional<Authentication> reject(ResolvedIdentityProvider provider, String reason) {
        Counter.builder(CLAIM_REJECTION_METRIC)
                .tag(IssuerSignatureVerifier.PROVIDER_TAG, provider.name())
                .tag(REASON_TAG, reason)
                .register(meterRegistry)
                .increment();
        return Optional.empty();
    }

    /**
     * The required {@code sub}, or {@code null} if absent. Unique per issuer only: key on the
     * principal's identifier, or pair it with {@link #IDENTITY_PROVIDER_ISSUER_ATTRIBUTE}.
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
     * Checks time claims under the provider's {@code clock-skew}. {@code exp} is required; {@code
     * iat} and {@code nbf} are honoured when present (RFC 7519 makes them optional).
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
     * Requires the identifier claim of <em>any</em> mapped role ({@link Role#CATALOG} needs none);
     * the acting role's own identifier is enforced later as a {@code 403}. See ADR-0006.
     */
    private boolean hasRequiredIdentifier(
            JWTClaimsSet claims, IdentityProviderConfiguration configuration, Set<Role> roles) {
        if (roles.isEmpty()
                || roles.stream().anyMatch(role -> identifies(role, claims, configuration))) {
            return true;
        }
        LOG.debug("A token carries the identifier claim of none of the roles it grants");
        return false;
    }

    /** Whether the token carries the identifier claim that one role is identified by. */
    private boolean identifies(
            Role role, JWTClaimsSet claims, IdentityProviderConfiguration configuration) {
        IdentityProviderConfiguration.ClaimsConfiguration claimNames = configuration.getClaims();
        return switch (role) {
            case USER -> claimMapper.findString(claims, claimNames.getUserIdentifier()).isPresent();
            case PARTICIPANT ->
                    claimMapper
                            .findString(claims, claimNames.getParticipantIdentifier())
                            .isPresent();
            case CATALOG -> true;
        };
    }

    /**
     * Parses without trusting; {@code null} unless a well-formed signed JWT, so {@code alg: none}
     * is refused here.
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

    /** The claim set, or {@code null} if the payload is not one. */
    @Nullable
    private static JWTClaimsSet claimsOf(SignedJWT jwt) {
        try {
            return jwt.getJWTClaimsSet();
        } catch (ParseException e) {
            LOG.debug("A bearer token carried a payload that is not a claim set", e);
            return null;
        }
    }

    /** Converts a date claim, {@code null} when absent. */
    @Nullable
    private static Instant toInstant(@Nullable Date claim) {
        return claim == null ? null : claim.toInstant();
    }
}
