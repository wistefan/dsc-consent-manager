package com.seamware.consentmanager.security;

/**
 * The dataspace catalog service, acting for the dataspace rather than for one organisation. Carries
 * no identifier by design: its read access spans the dataspace, so nothing scopes it.
 */
public record CatalogPrincipal(String issuer, String subject) implements ConsentManagerPrincipal {

    /** Returns {@link Role#CATALOG}. */
    @Override
    public Role role() {
        return Role.CATALOG;
    }
}
