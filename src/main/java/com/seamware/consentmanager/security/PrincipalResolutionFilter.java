package com.seamware.consentmanager.security;

import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.repository.ParticipantRepository;
import com.seamware.consentmanager.service.UserService;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.order.Ordered;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ServerFilter;
import io.micronaut.http.filter.ServerFilterPhase;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.authentication.AuthorizationException;
import io.micronaut.security.filters.SecurityFilter;
import io.micronaut.web.router.MethodBasedRouteMatch;
import io.micronaut.web.router.RouteAttributes;
import io.micronaut.web.router.RouteMatch;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Turns the validated {@link Authentication} into a typed {@link ConsentManagerPrincipal} and
 * attaches it to the request, so handlers read the caller from the token and nothing else.
 *
 * <p>Runs immediately after {@link SecurityFilter}, which publishes the authentication, and only
 * for a route that declares a {@link ConsentManagerPrincipal} parameter. The second condition
 * matters: an authenticated request may arrive at a route the specification declares {@code
 * security: []} - {@code /api-status}, {@code /health}, the Swagger assets - and resolving there
 * would let a token naming no usable caller turn a public reachability probe into a {@code 403}. It
 * also keeps the database work - the participant lookup and the user provisioning - off every
 * request that would never read its result.
 *
 * <p>Everything this filter refuses is a {@code 403}, never a {@code 401}: the token authenticated,
 * it simply does not name a caller this service will act for. That covers a token granting no role
 * the route accepts, one missing the identifier claim its acting role requires, and a {@code
 * PARTICIPANT} whose identifier is not registered - participants are created explicitly
 * (TICKET-005), never on the strength of a token.
 *
 * <p>A {@code USER} is the one identity a token does create: {@link UserService} gives every {@link
 * UserPrincipal} a persisted row, plus the signal saying whether this request inserted it.
 */
@Singleton
@ServerFilter(ServerFilter.MATCH_ALL_PATTERN)
public class PrincipalResolutionFilter implements Ordered {

    /**
     * Request attribute holding the resolved {@link ConsentManagerPrincipal}.
     *
     * <p>Read it through a typed controller parameter (see {@link PrincipalArgumentBinder}) rather
     * than by name.
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

    private final UserService userService;

    private final Scheduler blocking;

    /**
     * Creates the filter.
     *
     * @param registry resolves the verified issuer back to the provider whose claim names apply
     * @param claimMapper reads claims by those configured names
     * @param participants resolves a participant identifier to its registered row
     * @param userService backs a user identifier with the row the principal carries
     * @param blockingExecutor carries the JDBC work off the event loop, so a request that takes no
     *     principal pays no thread hop
     */
    public PrincipalResolutionFilter(
            IdentityProviderRegistry registry,
            ClaimMapper claimMapper,
            ParticipantRepository participants,
            UserService userService,
            @Named(TaskExecutors.BLOCKING) ExecutorService blockingExecutor) {
        this.registry = registry;
        this.claimMapper = claimMapper;
        this.participants = participants;
        this.userService = userService;
        this.blocking = Schedulers.fromExecutorService(blockingExecutor);
    }

    /**
     * Resolves the principal before the route runs, failing the request if it cannot be resolved.
     *
     * <p>Emits the request itself rather than completing empty: {@code MethodFilter} reads a {@code
     * Publisher<Void>} as logically void and never subscribes to it, so the attribute below would
     * silently never be set.
     *
     * @throws AuthorizationException {@code 403} when the token names no caller to act for
     */
    @RequestFilter
    public Publisher<HttpRequest<?>> resolvePrincipal(HttpRequest<?> request) {
        RouteMatch<?> route = RouteAttributes.getRouteMatch(request).orElse(null);
        Optional<Authentication> authentication =
                request.getAttribute(SecurityFilter.AUTHENTICATION, Authentication.class);
        if (authentication.isEmpty() || !declaresPrincipal(route)) {
            return Mono.just(request);
        }
        return resolve(authentication.get(), route)
                .map(
                        principal -> {
                            request.setAttribute(PRINCIPAL_ATTRIBUTE, principal);
                            return request;
                        });
    }

    /** Places this filter directly after the security filter, which publishes what it reads. */
    @Override
    public int getOrder() {
        return ServerFilterPhase.SECURITY.after();
    }

    /**
     * Reports whether the matched route takes a principal, which is the only way one is ever read.
     *
     * <p>{@link PrincipalArgumentBinder} is the sole consumer of {@link #PRINCIPAL_ATTRIBUTE}, so a
     * route with no such parameter cannot observe the result and must not be able to fail on it.
     */
    private static boolean declaresPrincipal(@Nullable RouteMatch<?> route) {
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
     * Builds the principal the token acts as on this route.
     *
     * @throws AuthorizationException {@code 403} when the token names no caller to act for
     */
    private Mono<ConsentManagerPrincipal> resolve(
            Authentication authentication, RouteMatch<?> route) {
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
        Optional<Role> effective =
                Role.effective(grantedRoles(authentication), acceptedRoles(route));
        if (effective.isEmpty()) {
            throw forbidden(authentication, "it grants no role this operation acts for");
        }
        String subject = authentication.getName();
        return switch (effective.get()) {
            case USER ->
                    Mono.<ConsentManagerPrincipal>fromCallable(
                                    () ->
                                            user(
                                                    authentication,
                                                    configuration,
                                                    issuer,
                                                    subject,
                                                    claims))
                            .subscribeOn(blocking);
            case CATALOG -> Mono.just(new CatalogPrincipal(issuer, subject));
            case PARTICIPANT ->
                    Mono.<ConsentManagerPrincipal>fromCallable(
                                    () ->
                                            participant(
                                                    authentication,
                                                    configuration,
                                                    issuer,
                                                    subject,
                                                    claims))
                            .subscribeOn(blocking);
        };
    }

    /**
     * The roles the token granted, read back from the authorities {@link
     * ConsentManagerTokenValidator} minted rather than re-derived from the raw claims.
     *
     * <p>Those authorities are {@link Role#name()} values and are also what {@code @Secured}
     * matches on, so round-tripping them is what keeps authorization and identity from deriving the
     * same fact twice and disagreeing.
     */
    private static Set<Role> grantedRoles(Authentication authentication) {
        EnumSet<Role> granted = EnumSet.noneOf(Role.class);
        for (String authority : authentication.getRoles()) {
            Role.fromConfiguredName(authority).ifPresent(granted::add);
        }
        return granted;
    }

    /**
     * The roles the matched route names in its {@code @Secured} rule, empty when it names none.
     *
     * <p>This is what keeps identity and authorization from diverging on a multi-role token: the
     * caller acts as a role the operation itself accepts, rather than as whichever role a
     * route-blind precedence happened to pick.
     */
    private static Set<Role> acceptedRoles(RouteMatch<?> route) {
        EnumSet<Role> accepted = EnumSet.noneOf(Role.class);
        for (String value : route.getAnnotationMetadata().stringValues(Secured.class)) {
            Role.fromConfiguredName(value).ifPresent(accepted::add);
        }
        return accepted;
    }

    /**
     * Builds a {@link UserPrincipal} carrying its provisioned row, so {@link UserPrincipal#user()}
     * is never {@code null} at a handler. Touches the database, so it runs on {@link #blocking}.
     *
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
        UserPrincipal principal =
                new UserPrincipal(
                        issuer,
                        subject,
                        identifier.get(),
                        claimMapper.findString(claims, EMAIL_CLAIM).orElse(null),
                        claimMapper.isTrue(claims, EMAIL_VERIFIED_CLAIM),
                        claimMapper.findString(claims, NAME_CLAIM).orElse(null),
                        claimMapper.findString(claims, GIVEN_NAME_CLAIM).orElse(null),
                        claimMapper.findString(claims, FAMILY_NAME_CLAIM).orElse(null),
                        null,
                        false);
        return principal.withUser(userService.provisionFromToken(principal));
    }

    /**
     * Builds a {@link ParticipantPrincipal} from the registered participant the token's identifier
     * resolves to. Reads the database, so it runs on {@link #blocking}.
     *
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

    /** Builds the {@code 403} refusal and records why at debug level only. */
    private static AuthorizationException forbidden(
            @Nullable Authentication authentication, String reason) {
        LOG.debug("Refusing an authenticated request because {}", reason);
        return new AuthorizationException(authentication);
    }
}
