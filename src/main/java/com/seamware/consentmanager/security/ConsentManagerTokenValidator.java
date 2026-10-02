package com.seamware.consentmanager.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
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
 * <p>This is the single entry point through which a request becomes authenticated. It is a
 * Micronaut Security {@link TokenValidator}, so {@code TokenAuthenticationFetcher} hands it every
 * bearer token the {@code Authorization} header carries. An empty publisher means "this token
 * authenticates nobody", which the security filter renders as {@code 401}.
 *
 * <h2>The pipeline</h2>
 *
 * <p>The order below is the whole security argument of this class, and each step exists because the
 * one before it has not yet established anything:
 *
 * <ol>
 *   <li><strong>Parse, trusting nothing.</strong> The token is decoded only so its header and
 *       claims can be <em>read</em>. Nothing read here is believed; it is all attacker-supplied
 *       until step 4 says otherwise.
 *   <li><strong>Check the algorithm against an allow-list.</strong> {@link
 *       #PERMITTED_SIGNATURE_ALGORITHMS} names the asymmetric algorithms this service accepts, and
 *       the check happens <em>before</em> any key is looked up. That ordering is the point: a
 *       symmetric algorithm must be refused because of what it is, not because no HMAC secret
 *       happened to be configured. See the discussion below.
 *   <li><strong>Resolve the issuer against the trust list.</strong> {@link
 *       IdentityProviderRegistry#findByIssuer(String)} returns a provider only if it is configured
 *       <em>and</em> its discovery has succeeded, so an unconfigured issuer, a provider still
 *       starting up and a permanently failed one are one answer, not three.
 *   <li><strong>Verify the signature against that issuer's keys.</strong> Delegated whole to {@link
 *       IssuerSignatureVerifier}: which key set, which {@code kid}, which cache. Crucially the
 *       token is only ever offered to the keys of the issuer it claims, so a key valid at one
 *       provider cannot satisfy a token claiming another.
 *   <li><strong>Validate the time claims</strong> against the provider's configured clock skew.
 *   <li><strong>Validate the audience</strong> against that provider's configured audience.
 *   <li><strong>Map the roles</strong> through that provider's role mapping.
 *   <li><strong>Require the identifier the mapped roles imply.</strong>
 * </ol>
 *
 * <p>Steps 5 to 8 run only on a token whose signature has already verified, so from there on the
 * claims are the provider's statements rather than the caller's.
 *
 * <h2>Why the algorithm allow-list is not redundant</h2>
 *
 * <p>It would be tempting to argue that only asymmetric keys are ever configured, so a symmetric
 * algorithm could never verify anyway. That argument is exactly the one that fails in practice. The
 * classic attack takes an RSA public key the provider publishes - which is public, by design - and
 * uses its modulus as an HMAC secret to sign a token with {@code HS256}. A verifier that selects a
 * key by {@code kid} and then trusts the header's {@code alg} to decide what to do with it will
 * happily verify that token. Naming the acceptable algorithms up front removes the header's say in
 * the matter: a token is refused for its {@code alg} before any key material is in scope, so the
 * guarantee holds no matter what the issuer publishes or how the key-selection layer behaves.
 *
 * <p>{@code alg: none} is refused by the same list, and twice over: an unsigned token has no
 * signature, so it fails to parse as a {@link SignedJWT} in the first place.
 *
 * <p>The list deliberately holds only {@code RS*}, {@code PS*} and {@code ES*} - the exact set the
 * token contract in {@code api/components/security.yaml} publishes. {@code EdDSA} is absent not
 * because it is weak but because it is not in the contract; adding it is a change to the contract
 * and to this constant together, which is the point of keeping them in step.
 *
 * <h2>What a rejection says</h2>
 *
 * <p>Every rejection is the same rejection. There is one failure signal - an empty publisher - and
 * no exception type, message or header distinguishes a bad signature from an unknown issuer from an
 * expired token. US-ID-008 requires that a caller cannot learn which issuers this deployment
 * trusts, and a 401 that said "unknown issuer" for one value and "bad signature" for another would
 * be a perfectly serviceable oracle for enumerating the trust list. The submitted issuer is
 * therefore never echoed back and is logged at {@code DEBUG} only.
 *
 * <h2>Relationship to the framework's own JWT validator</h2>
 *
 * <p>{@code micronaut-security-jwt} ships its own {@link TokenValidator} beans, {@code
 * NimbusJsonWebTokenValidator} and {@code NimbusReactiveJsonWebTokenValidator}. They are switched
 * off in {@code application.yml} through the module's own {@code
 * micronaut.security.token.jwt.nimbus.validator} and {@code ...nimbus.reactive-validator}
 * properties. {@code TokenAuthenticationFetcher} consults every registered validator in turn and
 * takes the first {@link Authentication} any of them produces, so leaving them registered would
 * mean a token this class refuses could still be admitted by one of them against a declarative
 * signature configuration this service does not have. Being stricter than a validator running
 * beside you buys nothing. {@code TokenSignatureEnforcementIT} pins that this class is the only
 * {@code TokenValidator} in a started context.
 *
 * @see ClaimMapper
 * @see IssuerSignatureVerifier
 * @see IdentityProviderRegistry
 */
@Singleton
public class ConsentManagerTokenValidator implements TokenValidator<HttpRequest<?>> {

    /**
     * The signature algorithms a token may be signed with.
     *
     * <p>Asymmetric only, and exactly the set published in the token contract at {@code
     * api/components/security.yaml}. Every symmetric algorithm ({@code HS256}, {@code HS384},
     * {@code HS512}) is absent on purpose: a shared secret would make this resource server capable
     * of minting the very tokens it validates, and it is what an attacker reaches for when turning
     * a published RSA modulus into an HMAC key.
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
     * Attribute under which the validated {@link Authentication} carries the name of the identity
     * provider that issued the token.
     *
     * <p>Principal resolution needs the provider's configuration - its claim names above all - to
     * read anything further out of the token, and re-deriving it from the {@code iss} claim would
     * re-do a lookup that has already happened. The key is namespaced so it cannot be confused with
     * a claim, and it is written <em>after</em> the raw claims, so a token that carries a claim
     * under this exact name overwrites nothing: the value here is always this service's, never the
     * caller's.
     */
    public static final String IDENTITY_PROVIDER_ATTRIBUTE = "consent-manager.identity-provider";

    private static final Logger LOG = LoggerFactory.getLogger(ConsentManagerTokenValidator.class);

    private final IdentityProviderRegistry registry;
    private final IssuerSignatureVerifier signatureVerifier;
    private final ClaimMapper claimMapper;

    /**
     * Creates the validator.
     *
     * @param registry the trust list, consulted to resolve a token's issuer
     * @param signatureVerifier the per-issuer signature check
     * @param claimMapper reads claims by the names the issuing provider was configured with
     */
    public ConsentManagerTokenValidator(
            IdentityProviderRegistry registry,
            IssuerSignatureVerifier signatureVerifier,
            ClaimMapper claimMapper) {
        this.registry = registry;
        this.signatureVerifier = signatureVerifier;
        this.claimMapper = claimMapper;
    }

    /**
     * Validates a bearer token and, if it holds up, describes who presented it.
     *
     * <p>Non-blocking throughout: the signature check may have to fetch an issuer's key set, so the
     * result is composed rather than awaited.
     *
     * @param token the raw token taken from the {@code Authorization} header
     * @param request the request it arrived on; unused, and may be {@code null} when a token is
     *     validated outside a request
     * @return a publisher emitting one {@link Authentication}, or emitting nothing if the token is
     *     not valid for any reason whatsoever
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
     * Applies every check that only makes sense once the signature has verified.
     *
     * @param provider the trust-list entry whose keys signed the token
     * @param claims the claims that signature covers
     * @return the authenticated caller, or {@link Optional#empty()} if the token fails any
     *     remaining check
     */
    private Optional<Authentication> authenticate(
            ResolvedIdentityProvider provider, JWTClaimsSet claims) {
        IdentityProviderConfiguration configuration = provider.configuration();
        if (!hasValidTimeClaims(claims, configuration.getClockSkew())) {
            return Optional.empty();
        }
        if (!claims.getAudience().contains(configuration.getAudience())) {
            LOG.debug("A token from provider '{}' names another audience", provider.name());
            return Optional.empty();
        }
        String subject = claims.getSubject();
        if (subject == null || subject.isBlank()) {
            LOG.debug("A token from provider '{}' carries no subject", provider.name());
            return Optional.empty();
        }
        Set<Role> roles = claimMapper.mapRoles(claims, configuration);
        if (!hasRequiredIdentifier(claims, configuration, roles)) {
            return Optional.empty();
        }
        Map<String, Object> attributes = new LinkedHashMap<>(claims.getClaims());
        attributes.put(IDENTITY_PROVIDER_ATTRIBUTE, provider.name());
        List<String> authorities = roles.stream().map(Role::name).toList();
        return Optional.of(Authentication.build(subject, authorities, attributes));
    }

    /**
     * Checks {@code exp}, {@code iat} and {@code nbf} against the provider's tolerance.
     *
     * <p>{@code exp} and {@code iat} are <strong>required</strong>, matching the token contract in
     * {@code api/components/security.yaml}, which marks both mandatory for all three token types. A
     * bearer token without an expiry never stops being valid, so treating a missing {@code exp} as
     * "nothing to check" would accept exactly the token that most needs refusing. {@code nbf} is
     * optional and honoured when present.
     *
     * <p>The tolerance is applied symmetrically and is the provider's own {@code clock-skew}
     * setting, because the clocks that may disagree are this service's and that provider's.
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
        if (issuedAt == null) {
            LOG.debug("A token carries no iat claim");
            return false;
        }
        if (issuedAt.minus(clockSkew).isAfter(now)) {
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
     * Requires the identifier claim that the token's mapped roles imply.
     *
     * <p>A {@link Role#USER} token has to say which user it acts for and a {@link Role#PARTICIPANT}
     * token which participant, because every authorization decision downstream is made against that
     * identifier; a token that carries the role but not the identifier would authenticate as
     * somebody this service cannot name. {@link Role#CATALOG} is exempt by contract - the catalog
     * acts for the dataspace rather than for one organisation and is identified by its issuer and
     * subject alone.
     *
     * <p>A token that maps to <em>no</em> role is also exempt: there is no role-specific identifier
     * to demand of it. Such a token authenticates with no authority at all and is refused by the
     * {@code @Secured} check on whatever it tries to reach, with {@code 403}.
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
     * Decodes a token far enough to read it, without believing any of it.
     *
     * <p>An unsigned {@code alg: none} token has an empty signature and does not parse as a {@link
     * SignedJWT} at all, so it is refused here before the algorithm allow-list ever sees it. Both
     * refusals are intended; neither is relied upon alone.
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
