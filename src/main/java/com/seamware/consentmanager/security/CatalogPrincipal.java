package com.seamware.consentmanager.security;

/**
 * The dataspace catalog service, acting for the dataspace rather than for a single organisation.
 *
 * <p>Carries no identifier beyond issuer and subject by design: a catalog token grants read access
 * across the dataspace, so there is no single organisation to scope it to and nothing for it to be
 * resolved against in this service's own tables.
 *
 * @param issuer the verified {@code iss}
 * @param subject the verified {@code sub}
 */
public record CatalogPrincipal(String issuer, String subject) implements ConsentManagerPrincipal {

    /** Returns {@link Role#CATALOG}. */
    @Override
    public Role role() {
        return Role.CATALOG;
    }
}
