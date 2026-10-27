package com.seamware.consentmanager.security;

import io.micronaut.context.annotation.Factory;
import io.micronaut.http.bind.binders.TypedRequestArgumentBinder;
import jakarta.inject.Singleton;

/**
 * Registers one {@link PrincipalArgumentBinder} per declarable principal type. Binders match by
 * exact declared type, so the sealed supertype and each permitted shape need one.
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
