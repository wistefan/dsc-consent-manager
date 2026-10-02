package com.seamware.consentmanager.security;

import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.repository.ParticipantRepository;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.filter.HttpServerFilter;
import io.micronaut.http.filter.ServerFilterChain;
import io.micronaut.http.filter.ServerFilterPhase;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.authentication.AuthorizationException;
import io.micronaut.security.filters.SecurityFilter;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Turns the validated {@link Authentication} into a typed {@link ConsentManagerPrincipal} and
 * attaches it to the request, so handlers read the caller from the token and nothing else.
 *
 * <p>Runs immediately after {@link SecurityFilter}, which is what makes the authentication
 * available; requests that carry none pass through untouched, so anonymous routes keep working.
 *
 * <p>Everything this filter refuses is a {@code 403}, never a {@code 401}: the token authenticated,
 * it simply does not name a caller this service will act for. That covers a token granting no role
 * at all (an absent or wholly unmappable roles claim), one granting several (a caller is either a
 * person or a machine, and guessing which would be an authorization decision made by accident), one
 * missing the identifier claim its role requires, and a {@code PARTICIPANT} whose identifier is not
 * registered - participants are created explicitly (TICKET-005), never on the strength of a token.
 */
@Singleton
@Filter(Filter.MATCH_ALL_PATTERN)
public class PrincipalResolutionFilter implements HttpServerFilter {

    /**
     * Request attribute holding the resolved {@link ConsentManagerPrincipal}.
     *
     * <p>Namespaced to keep it clear of framework attributes. Read it through a typed controller
     * parameter (see {@code PrincipalArgumentBinder}) rather than by name.
     */
    public static final String PRINCIPAL_ATTRIBUTE = "consent-manager.principal";

    /** Standard OpenID Connect {@code email} claim. */
    private static final List<String> EMAIL_CLAIM = List.of("email");

    /** Standard OpenID Connect {@code email_verified} claim. */
    private static final List<String> EMAIL_VERIFIED_CLAIM = List.of("email_verified");

    /** Standard OpenID Connect {@code name} claim. */
    private static final List<String> NAME_CLAIM = List.of("name");

    /** Standard OpenID Connect {@code given_name} claim. */
    private static final List<String> GIVEN_NAME_CLAIM = List.of("given_name");

    /** Standard OpenID Connect {@code family_name} claim. */
    private static final List<String> FAMILY_NAME_CLAIM = List.of("family_name");

    private static final Logger LOG = LoggerFactory.getLogger(PrincipalResolutionFilter.class);

    private final IdentityProviderRegistry registry;

    private final ClaimMapper claimMapper;

    private final ParticipantRepository participants;

    /**
     * Creates the filter.
     *
     * @param registry resolves the verified issuer back to the provider whose claim names apply
     * @param claimMapper reads claims by those configured names
     * @param participants resolves a participant identifier to its registered row
     */
    public PrincipalResolutionFilter(
            IdentityProviderRegistry registry,
            ClaimMapper claimMapper,
            ParticipantRepository participants) {
        this.registry = registry;
        this.claimMapper = claimMapper;
        this.participants = participants;
    }

    /**
     * Resolves the principal before the route runs, failing the request if it cannot be resolved.
     *
     * @param request the request the security filter has already authenticated, or not
     * @param chain the remainder of the filter chain
     * @return the downstream response, or an {@link AuthorizationException} signalling {@code 403}
     */
    @Override
    public Publisher<MutableHttpResponse<?>> doFilter(
            HttpRequest<?> request, ServerFilterChain chain) {
        Optional<Authentication> authentication =
                request.getAttribute(SecurityFilter.AUTHENTICATION, Authentication.class);
        if (authentication.isEmpty()) {
            return chain.proceed(request);
        }
        return resolve(authentication.get())
                .doOnNext(principal -> request.setAttribute(PRINCIPAL_ATTRIBUTE, principal))
                .flatMapMany(principal -> chain.proceed(request));
    }

    /**
     * Places this filter directly after the security filter, which publishes the authentication it
     * reads.
     *
     * @return the order of {@link ServerFilterPhase#SECURITY}, one step later
     */
    @Override
    public int getOrder() {
        return ServerFilterPhase.SECURITY.after();
    }

    /**
     * Builds the principal the single granted role calls for.
     *
     * @param authentication the validated caller
     * @return the typed principal, or an error signalling {@code 403}
     */
    private Mono<ConsentManagerPrincipal> resolve(Authentication authentication) {
        Map<String, Object> claims = authentication.getAttributes();
        String issuer =
                (String)
                        claims.get(ConsentManagerTokenValidator.IDENTITY_PROVIDER_ISSUER_ATTRIBUTE);
        Optional<ResolvedIdentityProvider> provider = registry.findByIssuer(issuer);
        if (provider.isEmpty()) {
            // Only reachable if the trust list were mutable, which it is not; refuse rather than
            // guess which provider's claim names apply.
            return forbidden(authentication, "its issuer is no longer on the trust list");
        }
        IdentityProviderConfiguration configuration = provider.get().configuration();
        Set<Role> roles = claimMapper.mapRoles(claims, configuration);
        if (roles.size() != 1) {
            return forbidden(
                    authentication,
                    roles.isEmpty()
                            ? "it grants no role this service recognises"
                            : "it grants several roles at once");
        }
        Role role = roles.iterator().next();
        String subject = authentication.getName();
        return switch (role) {
            case USER -> user(authentication, configuration, issuer, subject, claims);
            case PARTICIPANT -> participant(authentication, configuration, issuer, subject, claims);
            case CATALOG -> Mono.just(new CatalogPrincipal(issuer, subject));
        };
    }

    /**
     * Builds a {@link UserPrincipal}. The {@link UserPrincipal#user()} row stays {@code null} here;
     * just-in-time provisioning fills it.
     *
     * @param authentication the validated caller
     * @param configuration the issuing provider's configuration, naming the identifier claim
     * @param issuer the verified issuer
     * @param subject the verified subject
     * @param claims the claims the signature covers
     * @return the principal, or an error signalling {@code 403}
     */
    private Mono<ConsentManagerPrincipal> user(
            Authentication authentication,
            IdentityProviderConfiguration configuration,
            String issuer,
            String subject,
            Map<String, Object> claims) {
        Optional<String> identifier =
                claimMapper.findString(claims, configuration.getClaims().getUserIdentifier());
        if (identifier.isEmpty()) {
            return forbidden(authentication, "it carries no user identifier claim");
        }
        return Mono.just(
                new UserPrincipal(
                        issuer,
                        subject,
                        identifier.get(),
                        claimMapper.findString(claims, EMAIL_CLAIM).orElse(null),
                        claimMapper.isTrue(claims, EMAIL_VERIFIED_CLAIM),
                        claimMapper.findString(claims, NAME_CLAIM).orElse(null),
                        claimMapper.findString(claims, GIVEN_NAME_CLAIM).orElse(null),
                        claimMapper.findString(claims, FAMILY_NAME_CLAIM).orElse(null),
                        null));
    }

    /**
     * Builds a {@link ParticipantPrincipal} from the registered participant the token's identifier
     * resolves to.
     *
     * <p>The lookup is a blocking database read, so it is moved off the event loop.
     *
     * @param authentication the validated caller
     * @param configuration the issuing provider's configuration, naming the identifier claim
     * @param issuer the verified issuer
     * @param subject the verified subject
     * @param claims the claims the signature covers
     * @return the principal, or an error signalling {@code 403}
     */
    private Mono<ConsentManagerPrincipal> participant(
            Authentication authentication,
            IdentityProviderConfiguration configuration,
            String issuer,
            String subject,
            Map<String, Object> claims) {
        Optional<String> identifier =
                claimMapper.findString(
                        claims, configuration.getClaims().getParticipantIdentifier());
        if (identifier.isEmpty()) {
            return forbidden(authentication, "it carries no participant identifier claim");
        }
        return Mono.fromCallable(() -> participants.findByIdentifier(identifier.get()))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(
                        found ->
                                found.<Mono<ConsentManagerPrincipal>>map(
                                                row -> Mono.just(principalOf(issuer, subject, row)))
                                        .orElseGet(
                                                () ->
                                                        forbidden(
                                                                authentication,
                                                                "its participant identifier is not"
                                                                        + " registered")));
    }

    /**
     * Wraps a registered participant row in its principal.
     *
     * @param issuer the verified issuer
     * @param subject the verified subject
     * @param row the registered participant
     * @return the principal
     */
    private static ConsentManagerPrincipal principalOf(
            String issuer, String subject, Participant row) {
        return new ParticipantPrincipal(issuer, subject, row.getIdentifier(), row);
    }

    /**
     * Refuses the request with {@code 403} and records why at debug level only.
     *
     * @param authentication the validated caller, which is what makes the failure a {@code 403}
     * @param reason why the token names no caller this service will act for
     * @param <T> the element type of the stream the caller is building
     * @return an always-failing publisher
     */
    private static <T> Mono<T> forbidden(@Nullable Authentication authentication, String reason) {
        LOG.debug("Refusing an authenticated request because {}", reason);
        return Mono.error(new AuthorizationException(authentication));
    }
}
