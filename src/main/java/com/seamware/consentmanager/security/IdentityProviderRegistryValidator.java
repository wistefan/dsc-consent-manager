package com.seamware.consentmanager.security;

import io.micronaut.context.annotation.Context;
import io.micronaut.context.exceptions.ConfigurationException;
import jakarta.annotation.PostConstruct;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fails application startup when the identity provider trust list is missing, empty or internally
 * inconsistent.
 *
 * <p>Bean Validation on the {@link IdentityProviderConfiguration} beans can only reject a
 * <em>malformed</em> entry; it cannot notice that the list is <em>absent</em>, because an absent
 * list simply produces no beans. This {@link Context}-scoped bean closes that gap: it is
 * instantiated eagerly during context startup, which both forces every trust-list entry to be
 * created (and therefore validated) and lets it assert that at least one entry exists.
 *
 * <p>The check is <strong>unconditional</strong>. It fires regardless of {@code
 * micronaut.security.enabled}, because a resource server with no trust list is misconfigured
 * whatever that toggle says, and a deployment that discovers the problem at the first authenticated
 * request rather than at startup has already failed silently.
 *
 * <p>Per the immutability rule for the trust list, this bean exposes an unmodifiable view of the
 * configured providers and offers no way to add, remove, replace or reload an entry.
 */
@Context
public class IdentityProviderRegistryValidator {

    private static final Logger LOG =
            LoggerFactory.getLogger(IdentityProviderRegistryValidator.class);

    private final List<IdentityProviderConfiguration> providers;

    /**
     * Captures the configured trust list in a stable, unmodifiable order.
     *
     * @param providers one bean per {@code consent-manager.identity-providers} entry, injected by
     *     Micronaut; empty when the list is absent
     */
    public IdentityProviderRegistryValidator(List<IdentityProviderConfiguration> providers) {
        this.providers =
                List.copyOf(
                        providers.stream()
                                .sorted(
                                        Comparator.comparingInt(
                                                IdentityProviderConfiguration::getIndex))
                                .toList());
    }

    /**
     * Returns the configured trust list.
     *
     * <p>The returned collection is unmodifiable and is fixed for the lifetime of the process.
     *
     * @return the configured providers, ordered by their position in configuration
     */
    public List<IdentityProviderConfiguration> getProviders() {
        return providers;
    }

    /**
     * Verifies the trust list during context startup.
     *
     * @throws ConfigurationException if no provider is configured or two providers declare the same
     *     issuer
     */
    @PostConstruct
    public void validate() {
        if (providers.isEmpty()) {
            throw new ConfigurationException(
                    "No identity provider is configured. The Consent Manager is an OAuth2 resource"
                            + " server and cannot validate any token without a trust list; configure"
                            + " at least one entry under '"
                            + IdentityProviderConfiguration.PREFIX
                            + "'.");
        }
        rejectDuplicateIssuers();
        LOG.info(
                "Identity provider trust list loaded with {} provider(s): {}",
                providers.size(),
                providers.stream().map(IdentityProviderConfiguration::getIssuer).toList());
    }

    /**
     * Rejects a trust list in which two entries claim the same issuer, which would make lookup by
     * issuer ambiguous.
     *
     * @throws ConfigurationException if an issuer appears more than once
     */
    private void rejectDuplicateIssuers() {
        Map<String, Integer> firstSeenAt = new HashMap<>();
        for (IdentityProviderConfiguration provider : providers) {
            Integer previousIndex =
                    firstSeenAt.putIfAbsent(provider.getIssuer(), provider.getIndex());
            if (previousIndex != null) {
                throw new ConfigurationException(
                        IdentityProviderConfiguration.PREFIX
                                + " declares issuer '"
                                + provider.getIssuer()
                                + "' at both index "
                                + previousIndex
                                + " and index "
                                + provider.getIndex()
                                + "; each issuer must appear exactly once.");
            }
        }
    }
}
