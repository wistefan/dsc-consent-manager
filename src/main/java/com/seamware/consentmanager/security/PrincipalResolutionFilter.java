package com.seamware.consentmanager.security;

import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.repository.ParticipantRepository;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.order.Ordered;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ServerFilter;
import io.micronaut.http.filter.ServerFilterPhase;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.authentication.AuthorizationException;
import io.micronaut.security.filters.SecurityFilter;
import io.micronaut.web.router.MethodBasedRouteMatch;
import io.micronaut.web.router.RouteMatchUtils;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns the validated {@link Authentication} into a typed {@link ConsentManagerPrincipal} and
 * attaches it to the request, so handlers read the caller from the token and nothing else.
 *
 * <p>Runs immediately after {@link SecurityFilter}, which is what makes the authentication
 * available, and only for a route that declares a {@link ConsentManagerPrincipal} parameter. Both
 * conditions matter: an authenticated request may well arrive at a route the specification declares
 * {@code security: []} - {@code /api-status}, {@code /health}, the Swagger assets - and resolving
 * there would let a token that names no usable caller turn a public reachability probe into a
 * {@code 403}. Gating on the route's signature instead also keeps the participant lookup below off
 * every request that would never read its result, so only a handler that actually takes a principal
 * pays for one, and only it can fail when the database is unreachable.
 *
 * <p>Everything this filter refuses is a {@code 403}, never a {@code 401}: the token authenticated,
 * it simply does not name a caller this service will act for. That covers a token granting no role
 * at all (an absent or wholly unmappable roles claim), one missing the identifier claim its role
 * requires, and a {@code PARTICIPANT} whose identifier is not registered - participants are created
 * explicitly (TICKET-005), never on the strength of a token. A token granting several roles is not
 * refused: {@link Role#effective(Set)} settles which one it acts as, by the precedence the
 * published token contract documents.
 */
@Singleton
@ServerFilter(ServerFilter.MATCH_ALL_PATTERN)
public class PrincipalResolutionFilter implements Ordered {

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
     * <p>Declared blocking because resolving a participant reads the {@code participants} table
     * over JDBC; the alternative, a reactive filter method, would park the lookup on another
     * scheduler and buy nothing, since every route that can reach this point is itself blocking.
     *
     * @param request the request the security filter has already authenticated, or not
     * @throws AuthorizationException {@code 403} when the token names no caller to act for
     */
    @RequestFilter
    @ExecuteOn(TaskExecutors.BLOCKING)
    public void resolvePrincipal(HttpRequest<?> request) {
        Optional<Authentication> authentication =
                request.getAttribute(SecurityFilter.AUTHENTICATION, Authentication.class);
        if (authentication.isEmpty() || !declaresPrincipal(request)) {
            return;
        }
        request.setAttribute(PRINCIPAL_ATTRIBUTE, resolve(authentication.get()));
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
     * Reports whether the matched route takes a principal, which is the only way one is ever read.
     *
     * <p>{@code PrincipalArgumentBinder} is the sole consumer of {@link #PRINCIPAL_ATTRIBUTE}, so a
     * route with no such parameter cannot observe the result and must not be able to fail on it.
     *
     * @param request the request, carrying the route the router matched before the security filter
     * @return {@code true} when a handler argument declares {@link ConsentManagerPrincipal} or one
     *     of its shapes
     */
    private static boolean declaresPrincipal(HttpRequest<?> request) {
        Object route = RouteMatchUtils.findRouteMatch(request).orElse(null);
        if (!(route instanceof MethodBasedRouteMatch<?, ?> method)) {
            return false;
        }
        for (Argument<?> argument : method.getArguments()) {
            if (ConsentManagerPrincipal.class.isAssignableFrom(argument.getType())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Builds the principal the token's effective role calls for.
     *
     * @param authentication the validated caller
     * @return the typed principal
     * @throws AuthorizationException {@code 403} when the token names no caller to act for
     */
    private ConsentManagerPrincipal resolve(Authentication authentication) {
        Map<String, Object> claims = authentication.getAttributes();
        String issuer =
                (String)
                        claims.get(ConsentManagerTokenValidator.IDENTITY_PROVIDER_ISSUER_ATTRIBUTE);
        Optional<ResolvedIdentityProvider> provider = registry.findByIssuer(issuer);
        if (provider.isEmpty()) {
            // The attribute is set by ConsentManagerTokenValidator alone, so this branch is how an
            // Authentication built by any other TokenValidator lands here - with a null issuer and
            // therefore no provider. Refuse rather than guess which provider's claim names apply.
            throw forbidden(authentication, "no trusted provider claims to have issued it");
        }
        IdentityProviderConfiguration configuration = provider.get().configuration();
        Set<Role> roles = claimMapper.mapRoles(claims, configuration);
        Optional<Role> effective = Role.effective(roles);
        if (effective.isEmpty()) {
            throw forbidden(authentication, "it grants no role this service recognises");
        }
        String subject = authentication.getName();
        return switch (effective.get()) {
            case USER -> user(authentication, configuration, issuer, subject, claims);
            case PARTICIPANT -> participant(authentication, configuration, issuer, subject, claims);
            case CATALOG -> new CatalogPrincipal(issuer, subject);
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
     * @return the principal
     * @throws AuthorizationException {@code 403} when the token carries no user identifier
     */
    private ConsentManagerPrincipal user(
            Authentication authentication,
            IdentityProviderConfiguration configuration,
            String issuer,
            String subject,
            Map<String, Object> claims) {
        Optional<String> identifier =
                claimMapper.findString(claims, configuration.getClaims().getUserIdentifier());
        if (identifier.isEmpty()) {
            throw forbidden(authentication, "it carries no user identifier claim");
        }
        return new UserPrincipal(
                issuer,
                subject,
                identifier.get(),
                claimMapper.findString(claims, EMAIL_CLAIM).orElse(null),
                claimMapper.isTrue(claims, EMAIL_VERIFIED_CLAIM),
                claimMapper.findString(claims, NAME_CLAIM).orElse(null),
                claimMapper.findString(claims, GIVEN_NAME_CLAIM).orElse(null),
                claimMapper.findString(claims, FAMILY_NAME_CLAIM).orElse(null),
                null);
    }

    /**
     * Builds a {@link ParticipantPrincipal} from the registered participant the token's identifier
     * resolves to.
     *
     * @param authentication the validated caller
     * @param configuration the issuing provider's configuration, naming the identifier claim
     * @param issuer the verified issuer
     * @param subject the verified subject
     * @param claims the claims the signature covers
     * @return the principal
     * @throws AuthorizationException {@code 403} when the identifier is absent or unregistered
     */
    private ConsentManagerPrincipal participant(
            Authentication authentication,
            IdentityProviderConfiguration configuration,
            String issuer,
            String subject,
            Map<String, Object> claims) {
        Optional<String> identifier =
                claimMapper.findString(
                        claims, configuration.getClaims().getParticipantIdentifier());
        if (identifier.isEmpty()) {
            throw forbidden(authentication, "it carries no participant identifier claim");
        }
        Participant row =
                participants
                        .findByIdentifier(identifier.get())
                        .orElseThrow(
                                () ->
                                        forbidden(
                                                authentication,
                                                "its participant identifier is not registered"));
        return new ParticipantPrincipal(issuer, subject, row.getIdentifier(), row);
    }

    /**
     * Builds the {@code 403} refusal and records why at debug level only.
     *
     * @param authentication the validated caller, which is what makes the failure a {@code 403}
     * @param reason why the token names no caller this service will act for
     * @return the exception to throw
     */
    private static AuthorizationException forbidden(
            @Nullable Authentication authentication, String reason) {
        LOG.debug("Refusing an authenticated request because {}", reason);
        return new AuthorizationException(authentication);
    }
}
