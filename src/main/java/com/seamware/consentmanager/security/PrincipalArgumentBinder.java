package com.seamware.consentmanager.security;

import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.bind.binders.TypedRequestArgumentBinder;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.authentication.AuthorizationException;
import io.micronaut.security.filters.SecurityFilter;
import java.util.Optional;

/**
 * Binds a controller parameter declared as a {@link ConsentManagerPrincipal}, or as one of its
 * three concrete shapes, to the principal {@link PrincipalResolutionFilter} resolved.
 *
 * <p>A principal of another shape is a {@code 403}, not a binding failure: the route's
 * {@code @Secured} rule and its signature disagree, and refusal is the safe reading of that.
 *
 * @param <T> the principal type the bound parameter declares
 */
public final class PrincipalArgumentBinder<T extends ConsentManagerPrincipal>
        implements TypedRequestArgumentBinder<T> {

    private final Argument<T> argumentType;

    /** Creates a binder for one principal type. */
    public PrincipalArgumentBinder(Class<T> principalType) {
        this.argumentType = Argument.of(principalType);
    }

    /** The parameter type this binder is registered for. */
    @Override
    public Argument<T> argumentType() {
        return argumentType;
    }

    /**
     * Supplies the resolved principal.
     *
     * @throws AuthorizationException {@code 401} if no principal was resolved, {@code 403} if the
     *     resolved one is not of the declared type
     */
    @Override
    public BindingResult<T> bind(ArgumentConversionContext<T> context, HttpRequest<?> source) {
        Object resolved =
                source.getAttribute(PrincipalResolutionFilter.PRINCIPAL_ATTRIBUTE).orElse(null);
        if (resolved == null) {
            throw new AuthorizationException(null);
        }
        if (!argumentType.getType().isInstance(resolved)) {
            throw new AuthorizationException(authenticationOf(source));
        }
        T principal = argumentType.getType().cast(resolved);
        return () -> Optional.of(principal);
    }

    /** Reads the authentication back off the request, which is what tells a 403 from a 401. */
    private static Authentication authenticationOf(HttpRequest<?> source) {
        return source.getAttribute(SecurityFilter.AUTHENTICATION, Authentication.class)
                .orElse(null);
    }
}
