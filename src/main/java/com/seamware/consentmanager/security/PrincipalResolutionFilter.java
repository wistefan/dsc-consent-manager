package com.seamware.consentmanager.security;

import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.repository.ParticipantRepository;
import com.seamware.consentmanager.service.ProvisionedUser;
import com.seamware.consentmanager.service.UserService;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.order.Ordered;
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
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Turns the validated {@link Authentication} into a typed {@link ConsentManagerPrincipal} on the
 * request, right after {@link SecurityFilter}.
 *
 * <p>Runs only for a route declaring a principal parameter, so a token on a public route cannot
 * turn it into a {@code 403}. Every refusal is {@code 403}, never {@code 401}. An unregistered
 * participant resolves with no row so {@code POST /participants} is reachable; a {@code USER} is
 * provisioned.
 */
@Singleton
@ServerFilter(ServerFilter.MATCH_ALL_PATTERN)
public class PrincipalResolutionFilter implements Ordered {

    /** Request attribute holding the principal; read it via {@link PrincipalArgumentBinder}. */
    public static final String PRINCIPAL_ATTRIBUTE = "consent-manager.principal";

    private static final List<String> EMAIL_CLAIM = List.of("email");

    private static final List<String> EMAIL_VERIFIED_CLAIM = List.of("email_verified");

    private static final List<String> NAME_CLAIM = List.of("name");

    private static final List<String> GIVEN_NAME_CLAIM = List.of("given_name");

    private static final List<String> FAMILY_NAME_CLAIM = List.of("family_name");

    private static final Logger LOG = LoggerFactory.getLogger(PrincipalResolutionFilter.class);

    private final IdentityProviderRegistry registry;

    private final ClaimMapper claimMapper;

    private final ParticipantRepository participants;

    private final UserService userService;

    private final Scheduler blocking;

    /** Creates the filter; JDBC work runs on {@code blockingExecutor}, off the event loop. */
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
     * Resolves the principal before the route runs. Emits the request, not empty: a {@code
     * Publisher<Void>} is never subscribed, so the attribute would never be set.
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

    /** Directly after the security filter, which publishes what this one reads. */
    @Override
    public int getOrder() {
        return ServerFilterPhase.SECURITY.after();
    }

    /** Whether the route takes a principal parameter, the only way the attribute is ever read. */
    private static boolean declaresPrincipal(@Nullable RouteMatch<?> route) {
        if (!(route instanceof MethodBasedRouteMatch<?, ?> method)) {
            return false;
        }
        return Arrays.stream(method.getArguments())
                .anyMatch(
                        argument ->
                                ConsentManagerPrincipal.class.isAssignableFrom(argument.getType()));
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
            // Only ConsentManagerTokenValidator sets the issuer attribute; refuse any other
            // validator's Authentication rather than guess which claim names apply.
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
     * The granted roles, read from the authorities {@code @Secured} also matches on rather than
     * re-derived from claims, so identity and authorization cannot disagree.
     */
    private static Set<Role> grantedRoles(Authentication authentication) {
        return toRoles(authentication.getRoles().stream());
    }

    /** The roles the route's {@code @Secured} rule names; empty when it names none. */
    private static Set<Role> acceptedRoles(RouteMatch<?> route) {
        return toRoles(Arrays.stream(route.getAnnotationMetadata().stringValues(Secured.class)));
    }

    /** The roles the names denote; a name that is no role is skipped. */
    private static Set<Role> toRoles(Stream<String> names) {
        return names.map(Role::fromConfiguredName)
                .flatMap(Optional::stream)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(Role.class)));
    }

    /**
     * Builds a provisioned {@link UserPrincipal}, so {@code user()} is never {@code null} at a
     * handler. Runs on {@link #blocking}.
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
        ProvisionedUser provisioned = userService.provisionFromToken(principal);
        return principal.withUser(provisioned.user(), provisioned.created());
    }

    /**
     * Builds a {@link ParticipantPrincipal}, with its row when registered. Runs on {@link
     * #blocking}. An unregistered identifier is resolved, not refused, for self-registration.
     *
     * @throws AuthorizationException {@code 403} when the identifier claim is absent
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
        Participant row = participants.findByIdentifier(identifier.get()).orElse(null);
        return new ParticipantPrincipal(issuer, subject, identifier.get(), row);
    }

    /** Builds the {@code 403} refusal and records why at debug level only. */
    private static AuthorizationException forbidden(
            @Nullable Authentication authentication, String reason) {
        LOG.debug("Refusing an authenticated request because {}", reason);
        return new AuthorizationException(authentication);
    }
}
