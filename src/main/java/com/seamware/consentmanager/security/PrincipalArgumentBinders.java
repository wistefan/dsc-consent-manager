package com.seamware.consentmanager.security;

import io.micronaut.context.annotation.Factory;
import io.micronaut.http.bind.binders.TypedRequestArgumentBinder;
import jakarta.inject.Singleton;

/**
 * Registers one {@link PrincipalArgumentBinder} per declarable principal type.
 *
 * <p>The binder registry matches a parameter against a binder by its exact declared type, so the
 * sealed supertype and each of its three permitted shapes each need an entry. Adding a fourth
 * principal is a compile error in every exhaustive {@code switch} over {@link
 * ConsentManagerPrincipal}, which is where the reminder to add it here belongs.
 */
@Factory
public class PrincipalArgumentBinders {

    /** Binder for a parameter declared as the sealed supertype, for handlers serving every role. */
    @Singleton
    public TypedRequestArgumentBinder<ConsentManagerPrincipal> anyPrincipalBinder() {
        return new PrincipalArgumentBinder<>(ConsentManagerPrincipal.class);
    }

    /** Binder for a parameter declared as {@link UserPrincipal}. */
    @Singleton
    public TypedRequestArgumentBinder<UserPrincipal> userPrincipalBinder() {
        return new PrincipalArgumentBinder<>(UserPrincipal.class);
    }

    /** Binder for a parameter declared as {@link ParticipantPrincipal}. */
    @Singleton
    public TypedRequestArgumentBinder<ParticipantPrincipal> participantPrincipalBinder() {
        return new PrincipalArgumentBinder<>(ParticipantPrincipal.class);
    }

    /** Binder for a parameter declared as {@link CatalogPrincipal}. */
    @Singleton
    public TypedRequestArgumentBinder<CatalogPrincipal> catalogPrincipalBinder() {
        return new PrincipalArgumentBinder<>(CatalogPrincipal.class);
    }
}
