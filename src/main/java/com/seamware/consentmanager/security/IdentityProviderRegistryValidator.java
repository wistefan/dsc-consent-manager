package com.seamware.consentmanager.security;

import io.micronaut.context.annotation.Context;
import io.micronaut.context.env.Environment;
import io.micronaut.context.exceptions.ConfigurationException;
import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Assembles the identity provider trust list at startup and refuses to start when it is missing,
 * incomplete or internally inconsistent.
 *
 * <p>A trusted provider is described by two configuration blocks that must agree:
 *
 * <ul>
 *   <li>{@code micronaut.security.oauth2.clients.<name>.openid.issuer} — owned by Micronaut
 *       Security, which discovers the provider's metadata and maintains its signing-key cache.
 *   <li>{@code consent-manager.identity-providers.<name>} — this service's audience, clock skew,
 *       claim paths and role mapping for that issuer.
 * </ul>
 *
 * <p>Neither block is useful without the other, and neither framework validates the pairing: an
 * OpenID client with no matching settings would authenticate tokens this service cannot map to a
 * role, and settings with no matching client would describe an issuer nobody trusts. Both mistakes
 * are silent at runtime, so this bean rejects them at startup instead.
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

    /** Number of providers the interim global claim validators can cover. */
    private static final int SINGLE_PROVIDER = 1;

    /** Property holding the single issuer Micronaut's own claim validator enforces. */
    private static final String GLOBAL_ISSUER_VALIDATOR_PROPERTY =
            "micronaut.security.token.jwt.claims-validators.issuer";

    /**
     * URL schemes an issuer may use.
     *
     * <p>An issuer identifies a provider whose signing keys are fetched over the network, so only
     * the two HTTP schemes are meaningful. Anything else is a configuration mistake.
     */
    private static final Set<String> SUPPORTED_ISSUER_SCHEMES = Set.of("http", "https");

    /** Property suffix under which an OpenID Connect client declares its issuer. */
    private static final String ISSUER_PROPERTY_SUFFIX = ".openid.issuer";

    private final Environment environment;

    private final List<TrustedIdentityProvider> providers;

    /**
     * Joins the two configuration blocks into one trust list, in a stable order.
     *
     * @param environment the resolved configuration, used to read each issuer exactly as an
     *     operator wrote it rather than as Micronaut's {@code URL} conversion rewrote it
     * @param settings one bean per {@code consent-manager.identity-providers} entry, injected by
     *     Micronaut; empty when the block is absent
     * @throws ConfigurationException if the two blocks do not describe the same set of names
     */
    public IdentityProviderRegistryValidator(
            Environment environment, List<IdentityProviderConfiguration> settings) {
        this.environment = environment;
        this.providers = List.copyOf(join(settings));
    }

    /**
     * Returns the configured trust list.
     *
     * <p>The returned collection is unmodifiable and is fixed for the lifetime of the process.
     *
     * @return the trusted providers, ordered by name
     */
    public List<TrustedIdentityProvider> getProviders() {
        return providers;
    }

    /**
     * Verifies the assembled trust list during context startup.
     *
     * @throws ConfigurationException if no provider is configured or two providers declare the same
     *     issuer
     */
    @PostConstruct
    public void validate() {
        if (providers.isEmpty()) {
            throw new ConfigurationException(
                    "No identity provider is configured. The Consent Manager is an OAuth2 resource"
                            + " server and cannot validate any token without a trust list;"
                            + " configure at least one provider as '"
                            + IdentityProviderConfiguration.OIDC_CLIENTS_PREFIX
                            + ".<name>.openid.issuer' together with its claim settings under '"
                            + IdentityProviderConfiguration.PREFIX
                            + ".<name>'.");
        }
        rejectDuplicateIssuers();
        LOG.info(
                "Identity provider trust list loaded with {} provider(s): {}",
                providers.size(),
                providers.stream().map(p -> p.name() + " -> " + p.issuer()).toList());
        warnAboutTheInterimSingleProviderGuard();
    }

    /**
     * Warns that only one provider's tokens can currently pass validation.
     *
     * <p>Until the per-provider token validator lands, issuer and audience are enforced by
     * Micronaut's own claim validators, which hold a single global value each (see the {@code
     * micronaut.security.token.jwt.claims-validators} block in {@code application.yml}). Tokens
     * from every provider other than the first therefore fail validation. That is fail-closed and
     * deliberate, but it must not be silent: an operator who configures a second issuer would
     * otherwise see a clean startup and unexplained rejections.
     */
    private void warnAboutTheInterimSingleProviderGuard() {
        if (providers.size() <= SINGLE_PROVIDER) {
            return;
        }
        LOG.warn(
                "{} identity providers are configured, but issuer and audience are still enforced"
                        + " by Micronaut's global claim validators, which hold one value each:"
                        + " {}='{}'. Only tokens matching that issuer can pass validation; tokens"
                        + " from the other provider(s) will be rejected until the per-provider"
                        + " token validator is in place.",
                providers.size(),
                GLOBAL_ISSUER_VALIDATOR_PROPERTY,
                environment
                        .getProperty(GLOBAL_ISSUER_VALIDATOR_PROPERTY, String.class)
                        .orElse("<unset>"));
    }

    /**
     * Pairs each OpenID Connect client with the settings entry of the same name.
     *
     * <p>The client names are read from the {@link Environment} rather than from Micronaut
     * Security's {@code OpenIdClientConfiguration} beans, because those beans only exist while
     * {@code micronaut.security.enabled} is {@code true}. This check is deliberately unconditional
     * (see the class documentation), so it must not depend on that toggle: reading the raw
     * properties keeps an incomplete trust list fatal even in a context that starts with security
     * switched off, such as the test environment.
     *
     * @param settings the {@code consent-manager.identity-providers} beans
     * @return the joined trust list, ordered by name
     * @throws ConfigurationException if either block names a provider the other does not, or a
     *     client declares no or a malformed issuer
     */
    private List<TrustedIdentityProvider> join(List<IdentityProviderConfiguration> settings) {

        Map<String, IdentityProviderConfiguration> settingsByName = new LinkedHashMap<>();
        for (IdentityProviderConfiguration entry : settings) {
            settingsByName.put(entry.getName(), entry);
        }
        Collection<String> clientNames =
                environment.getPropertyEntries(IdentityProviderConfiguration.OIDC_CLIENTS_PREFIX);

        rejectUnpairedNames(settingsByName.keySet(), clientNames);

        List<TrustedIdentityProvider> joined = new ArrayList<>(clientNames.size());
        for (String name : clientNames) {
            joined.add(
                    new TrustedIdentityProvider(
                            name, requireIssuer(name), settingsByName.get(name)));
        }
        joined.sort(Comparator.comparing(TrustedIdentityProvider::name));
        return joined;
    }

    /**
     * Rejects a configuration in which the two blocks do not name the same providers.
     *
     * @param settingsNames the names found under {@code consent-manager.identity-providers}
     * @param clientNames the names found under {@code micronaut.security.oauth2.clients}
     * @throws ConfigurationException if either set contains a name the other does not
     */
    private void rejectUnpairedNames(
            Collection<String> settingsNames, Collection<String> clientNames) {

        var clientsWithoutSettings = new TreeSet<>(clientNames);
        clientsWithoutSettings.removeAll(settingsNames);
        if (!clientsWithoutSettings.isEmpty()) {
            throw new ConfigurationException(
                    "Identity provider(s) "
                            + clientsWithoutSettings
                            + " declare an issuer under '"
                            + IdentityProviderConfiguration.OIDC_CLIENTS_PREFIX
                            + "' but have no claim settings. Add '"
                            + IdentityProviderConfiguration.PREFIX
                            + ".<name>' with audience, claims and role-mapping for each, or remove"
                            + " the client: a token this service cannot map to a role would"
                            + " authenticate with no authority at all.");
        }

        var settingsWithoutClients = new TreeSet<>(settingsNames);
        settingsWithoutClients.removeAll(clientNames);
        if (!settingsWithoutClients.isEmpty()) {
            throw new ConfigurationException(
                    "Identity provider(s) "
                            + settingsWithoutClients
                            + " have claim settings under '"
                            + IdentityProviderConfiguration.PREFIX
                            + "' but no issuer. Add '"
                            + IdentityProviderConfiguration.OIDC_CLIENTS_PREFIX
                            + ".<name>.openid.issuer' for each, or remove the settings: without an"
                            + " issuer no token can ever match them.");
        }
    }

    /**
     * Extracts and checks an OpenID Connect client's issuer.
     *
     * <p>The issuer is read from the {@link Environment} as the raw string an operator configured,
     * <strong>not</strong> from Micronaut Security's converted value. Micronaut converts that
     * property to a {@link java.net.URL} and, in doing so, silently repairs a malformed value by
     * prefixing {@code http://} — {@code not-a-url} becomes {@code http://not-a-url}. Trusting the
     * converted value would therefore turn a typo into a plausible-looking trusted issuer that can
     * never match any token. The raw string is also the value a token's {@code iss} claim is
     * matched against, so it is the one that has to be well formed. (That match is currently a
     * protocol-stripped suffix comparison rather than an equality test — see {@link
     * TrustedIdentityProvider} — which makes a well-formed configured issuer matter more, not less:
     * everything the match keeps comes from this string.)
     *
     * @param name the provider name, used in the failure message
     * @return the issuer exactly as configured
     * @throws ConfigurationException if the issuer is absent or is not an absolute {@code http(s)}
     *     URL with a host
     */
    private String requireIssuer(String name) {
        String property =
                IdentityProviderConfiguration.OIDC_CLIENTS_PREFIX
                        + "."
                        + name
                        + ISSUER_PROPERTY_SUFFIX;
        String issuer = environment.getProperty(property, String.class).orElse("");
        if (issuer.isBlank()) {
            throw new ConfigurationException(
                    property
                            + " must be configured with the provider's issuer URL; without it no"
                            + " token can ever be matched to this provider.");
        }
        requireAbsoluteHttpUrl(property, issuer);
        return issuer;
    }

    /**
     * Asserts that a configured issuer is an absolute {@code http} or {@code https} URL with a
     * host.
     *
     * @param property the fully-qualified property name, used in the failure message
     * @param issuer the raw configured value
     * @throws ConfigurationException if the value is not such a URL
     */
    private void requireAbsoluteHttpUrl(String property, String issuer) {
        URI uri;
        try {
            uri = new URI(issuer);
        } catch (URISyntaxException e) {
            throw new ConfigurationException(property + " is not a valid URL: '" + issuer + "'", e);
        }
        String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
        if (scheme == null || !SUPPORTED_ISSUER_SCHEMES.contains(scheme) || uri.getHost() == null) {
            throw new ConfigurationException(
                    property
                            + " must be an absolute "
                            + SUPPORTED_ISSUER_SCHEMES
                            + " URL with a host, but was '"
                            + issuer
                            + "'");
        }
    }

    /**
     * Rejects a trust list in which two entries claim the same issuer, which would make lookup by
     * issuer ambiguous.
     *
     * @throws ConfigurationException if an issuer appears more than once
     */
    private void rejectDuplicateIssuers() {
        Map<String, String> firstSeenAs = new HashMap<>();
        for (TrustedIdentityProvider provider : providers) {
            String previousName = firstSeenAs.putIfAbsent(provider.issuer(), provider.name());
            if (previousName != null) {
                throw new ConfigurationException(
                        IdentityProviderConfiguration.OIDC_CLIENTS_PREFIX
                                + " declares issuer '"
                                + provider.issuer()
                                + "' for both '"
                                + previousName
                                + "' and '"
                                + provider.name()
                                + "'; each issuer must appear exactly once.");
            }
        }
    }
}
