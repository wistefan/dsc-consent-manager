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
 * Binds a controller parameter declared as a {@link ConsentManagerPrincipal} - or as one of its
 * three concrete shapes - to the principal {@link PrincipalResolutionFilter} resolved.
 *
 * <p>This is what makes taking the caller from the verified token the path of least resistance (AC
 * 15): a handler that needs to know who is calling declares the type it is willing to serve and
 * gets it, with no reason to read an identifier out of a path variable or request body.
 *
 * <p>A declared type the resolved principal does not match is a {@code 403}, not a binding failure:
 * reaching a {@code UserPrincipal} handler with a participant token means the route's
 * {@code @Secured} and its signature disagree, and the safe reading of that disagreement is
 * refusal.
 *
 * @param <T> the principal type the bound parameter declares
 */
public final class PrincipalArgumentBinder<T extends ConsentManagerPrincipal>
        implements TypedRequestArgumentBinder<T> {

    private final Argument<T> argumentType;

    /**
     * Creates a binder for one principal type.
     *
     * @param principalType the declared parameter type this binder serves
     */
    public PrincipalArgumentBinder(Class<T> principalType) {
        this.argumentType = Argument.of(principalType);
    }

    /**
     * The parameter type this binder is registered for.
     *
     * @return the argument type
     */
    @Override
    public Argument<T> argumentType() {
        return argumentType;
    }

    /**
     * Supplies the resolved principal.
     *
     * @param context the conversion context for the declared parameter
     * @param source the request the filter attached the principal to
     * @return the bound principal
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

    /**
     * Reads the authentication back off the request, which is what tells a {@code 403} from a
     * {@code 401}.
     *
     * @param source the request
     * @return the authentication, or {@code null} if the request carries none
     */
    private static Authentication authenticationOf(HttpRequest<?> source) {
        return source.getAttribute(SecurityFilter.AUTHENTICATION, Authentication.class)
                .orElse(null);
    }
}
