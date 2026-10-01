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
 *
 * <p><strong>Exactly one provider is accepted for now.</strong> Token validation is still delegated
 * to Micronaut Security, whose signature verification tries every configured key set and is not
 * bound to the token's issuer, so a second provider could sign tokens bearing the first provider's
 * {@code iss} and they would authenticate. Until step 5 binds each issuer to its own key set, a
 * multi-provider trust list is a startup failure rather than a warning — see {@link
 * #rejectMoreThanOneProvider()}.
 */
@Context
public class IdentityProviderRegistryValidator {

    private static final Logger LOG =
            LoggerFactory.getLogger(IdentityProviderRegistryValidator.class);

    /** Number of providers the interim global claim validators can cover. */
    private static final int SINGLE_PROVIDER = 1;

    /**
     * Property holding the single issuer Micronaut's own claim validator enforces.
     *
     * <p>Public because it names the interim guard that {@code application.yml} configures and that
     * the tests pin; it disappears together with the guard when per-provider validation lands.
     */
    public static final String GLOBAL_ISSUER_VALIDATOR_PROPERTY =
            "micronaut.security.token.jwt.claims-validators.issuer";

    /**
     * Property holding the single audience Micronaut's own claim validator enforces.
     *
     * <p>Public for the same reason as {@link #GLOBAL_ISSUER_VALIDATOR_PROPERTY}.
     */
    public static final String GLOBAL_AUDIENCE_VALIDATOR_PROPERTY =
            "micronaut.security.token.jwt.claims-validators.audience";

    /**
     * Name the sole trusted provider must be configured under until step 5.
     *
     * <p>The interim claim validators in {@code application.yml} cannot say "whichever provider is
     * configured": a YAML placeholder has to name one, and it names this one. A deployment that
     * configures its provider under any other name leaves {@code claims-validators.issuer} pointing
     * at an unresolvable placeholder, which aborts startup with a message about placeholder
     * resolution rather than about the trust list. Requiring the name here turns that into a
     * diagnosis. Step 5 replaces the global validators with per-provider ones and deletes both this
     * constant and the check that uses it.
     */
    private static final String INTERIM_GUARDED_PROVIDER_NAME = "primary";

    /** Keys a {@code consent-manager.identity-providers.<name>} entry may declare. */
    private static final Set<String> SUPPORTED_SETTING_KEYS =
            Set.of("audience", "clock-skew", "claims", "role-mapping");

    /** Key of the nested block holding the per-provider claim paths. */
    private static final String CLAIMS_KEY = "claims";

    /** Keys a {@code consent-manager.identity-providers.<name>.claims} block may declare. */
    private static final Set<String> SUPPORTED_CLAIM_KEYS =
            Set.of("user-identifier", "participant-identifier", "roles");

    /**
     * Keys that appear in the ticket's own configuration example but that this service does not
     * read, mapped to an explanation of where they went.
     *
     * <p>These are not typos: an operator who copies the YAML block out of the ticket writes them
     * verbatim, so "unrecognised key" on its own would be a confusing abort. Each entry says why
     * the key is absent and when, if ever, it returns.
     */
    private static final Map<String, String> KEYS_FROM_THE_TICKET_SCHEMA =
            Map.of(
                    "discovery-url",
                            "is not configured separately: Micronaut Security derives the"
                                    + " discovery URL from the issuer declared under '"
                                    + IdentityProviderConfiguration.OIDC_CLIENTS_PREFIX
                                    + ".<name>.openid.issuer', so remove it.",
                    "jwks-cache-ttl",
                            "is not yet expressible per provider: the signing-key cache is"
                                    + " currently global ('micronaut.caches.jwks.expire-after-write')"
                                    + " because one cache serves every provider. A per-provider TTL"
                                    + " arrives with the JWKS work in step 4.");

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
     * @throws ConfigurationException if no provider is configured, two providers declare the same
     *     issuer, more than one provider is configured, the interim claim validators do not resolve
     *     to the sole provider's issuer and audience, an entry declares an unrecognised key, or the
     *     unsigned-token guard is switched off outside a test context
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
        rejectMoreThanOneProvider();
        requireTheInterimGuardToCoverTheProvider();
        rejectUnrecognisedSettingKeys();
        rejectDisabledUnsignedTokenRejector();
        LOG.info(
                "Identity provider trust list loaded with {} provider(s): {}",
                providers.size(),
                providers.stream().map(p -> p.name() + " -> " + p.issuer()).toList());
    }

    /**
     * Refuses to start with more than one trusted provider, until per-provider token validation
     * lands in step 5.
     *
     * <p>This is a security check, not a limitation of the configuration binding: a second provider
     * would be able to forge tokens for the first. Signature verification is delegated to Micronaut
     * Security, and it is not bound to the issuer. Verified against micronaut-security 5.4.0,
     * {@code NimbusJsonWebTokenSignatureValidator.validate} is:
     *
     * <pre>{@code
     * for (SignatureConfiguration config : signatureConfigurations) {
     *     if (validate(jwt, config)) { return true; }
     * }
     * return false;
     * }</pre>
     *
     * <p>and the reactive path — the one the JWKS beans actually take — is the same iteration
     * expressed as a {@code Flux}. Neither reads the token's {@code iss}: every configured key set
     * is tried, and the first that verifies wins. Each OpenID client contributes its own key set,
     * so with providers A and B both configured, a token signed with <strong>B's</strong> key but
     * carrying {@code iss} of A passes signature validation on B's keys and then satisfies the
     * global issuer and audience validators, which only ever pin A. B can therefore mint tokens
     * that authenticate as A.
     *
     * <p>Rejecting the configuration outright is the only fail-closed option available before the
     * per-provider validator exists. Step 5 binds each issuer to its own key set and removes this
     * check.
     *
     * @throws ConfigurationException if more than one provider is configured
     */
    private void rejectMoreThanOneProvider() {
        if (providers.size() <= SINGLE_PROVIDER) {
            return;
        }
        throw new ConfigurationException(
                providers.size()
                        + " identity providers are configured ("
                        + providers.stream().map(TrustedIdentityProvider::name).toList()
                        + "), but only one is supported until per-provider token validation is in"
                        + " place. Micronaut Security verifies a token against every configured"
                        + " key set and stops at the first that matches, without checking which"
                        + " issuer the key belongs to, so a second provider could sign tokens"
                        + " bearing the first provider's issuer and they would authenticate."
                        + " Configure exactly one provider under '"
                        + IdentityProviderConfiguration.OIDC_CLIENTS_PREFIX
                        + "' and '"
                        + IdentityProviderConfiguration.PREFIX
                        + "'.");
    }

    /**
     * Refuses to start unless the interim global claim validators actually guard the sole trusted
     * provider.
     *
     * <p>Issuer and audience are not yet checked per provider. Until step 5 they are enforced by
     * Micronaut's two global claim validators, which hold one value each and are configured in
     * {@code application.yml} as placeholders naming the {@value #INTERIM_GUARDED_PROVIDER_NAME}
     * entry. Those placeholders are only a default: both properties are independently settable, so
     * {@code MICRONAUT_SECURITY_TOKEN_JWT_CLAIMS_VALIDATORS_AUDIENCE=something-else} silently aims
     * the only issuer and audience guard this step provides at a value no trusted provider uses,
     * and the service starts cleanly. Checking that the provider is <em>named</em> {@value
     * #INTERIM_GUARDED_PROVIDER_NAME} would not catch that: the name is a proxy for the property
     * resolving correctly, not the thing that matters.
     *
     * <p>So this asserts the resolved values themselves. The name requirement then falls out as a
     * consequence — a differently named provider leaves the placeholders unresolvable or pointing
     * elsewhere — and is reported with a dedicated message because it is by far the likeliest
     * cause.
     *
     * <p>The comparison is exact. That is stricter than the validator it guards: Micronaut's {@code
     * IssuerJwtClaimsValidator} suffix-matches a token's {@code iss} against this value. Being
     * strict here costs nothing and means the property cannot drift from the trust list unnoticed.
     *
     * @throws ConfigurationException if either property is unset, unresolvable, or resolves to
     *     anything other than the sole provider's issuer and audience
     */
    private void requireTheInterimGuardToCoverTheProvider() {
        TrustedIdentityProvider provider = providers.get(0);
        requireGuardResolvesTo(GLOBAL_ISSUER_VALIDATOR_PROPERTY, provider.issuer(), provider);
        requireGuardResolvesTo(
                GLOBAL_AUDIENCE_VALIDATOR_PROPERTY, provider.settings().getAudience(), provider);
    }

    /**
     * Asserts that one interim claim-validator property resolves to the value it is meant to guard.
     *
     * @param property the claim-validator property to read
     * @param expected the trust-list value it must equal
     * @param provider the sole trusted provider, for the failure message
     * @throws ConfigurationException if the property is unset, unresolvable or differs
     */
    private void requireGuardResolvesTo(
            String property, String expected, TrustedIdentityProvider provider) {

        String configured;
        try {
            configured = environment.getProperty(property, String.class).orElse(null);
        } catch (ConfigurationException e) {
            // An unresolvable `${...}` placeholder. The placeholders in application.yml name the
            // `primary` entry literally, because a placeholder cannot say "whichever provider is
            // configured", so this is what a renamed provider looks like. Micronaut's own message
            // talks about placeholder resolution and says nothing about the trust list.
            throw new ConfigurationException(
                    interimGuardFailure(property, provider)
                            + " It is configured as a placeholder that could not be resolved: "
                            + e.getMessage(),
                    e);
        }
        if (expected.equals(configured)) {
            return;
        }
        throw new ConfigurationException(
                interimGuardFailure(property, provider)
                        + " It must resolve to '"
                        + expected
                        + "', but resolved to "
                        + (configured == null ? "nothing" : "'" + configured + "'")
                        + ". Setting it independently of the trust list leaves tokens unguarded by"
                        + " the only issuer and audience check this service currently performs.");
    }

    /**
     * Builds the shared opening of an interim-guard failure message.
     *
     * @param property the claim-validator property at fault
     * @param provider the sole trusted provider
     * @return a sentence naming the property, the provider and the renaming trap
     */
    private static String interimGuardFailure(String property, TrustedIdentityProvider provider) {
        return "The interim claim validator '"
                + property
                + "' does not guard identity provider '"
                + provider.name()
                + "'. Until per-provider token validation is in place, issuer and audience are"
                + " enforced only by Micronaut's two global claim validators, which are configured"
                + " in application.yml as placeholders naming the '"
                + INTERIM_GUARDED_PROVIDER_NAME
                + "' entry; a provider under any other name, or an override of this property, "
                + "leaves them guarding nothing.";
    }

    /**
     * Refuses to start when a claim-settings entry declares a key this service does not read.
     *
     * <p>{@code @EachProperty} ignores unknown keys silently, so a typo, or a key taken from an
     * older revision of the configuration schema, binds to nothing and does nothing. Two keys make
     * that especially dangerous: {@code discovery-url} and {@code jwks-cache-ttl} both look as
     * though they configure the security boundary, and neither is read — discovery is derived from
     * the issuer by Micronaut Security, and the JWKS cache TTL is global rather than per provider
     * (see {@code application.yml}). An operator who sets either gets a clean startup and no
     * effect, which contradicts this class's promise that a malformed trust list aborts startup.
     *
     * <p>The role mapping is deliberately not checked here: its keys are role names, which {@link
     * IdentityProviderConfiguration} already validates.
     *
     * @throws ConfigurationException if an entry declares a key outside {@link
     *     #SUPPORTED_SETTING_KEYS}, or its {@code claims} block declares one outside {@link
     *     #SUPPORTED_CLAIM_KEYS}
     */
    private void rejectUnrecognisedSettingKeys() {
        for (TrustedIdentityProvider provider : providers) {
            String prefix = IdentityProviderConfiguration.PREFIX + "." + provider.name();
            rejectUnrecognisedKeys(prefix, SUPPORTED_SETTING_KEYS);
            rejectUnrecognisedKeys(prefix + "." + CLAIMS_KEY, SUPPORTED_CLAIM_KEYS);
        }
    }

    /**
     * Asserts that every configured key directly below a prefix is one this service reads.
     *
     * @param prefix the fully-qualified configuration prefix to inspect
     * @param supportedKeys the keys that prefix may declare
     * @throws ConfigurationException if any other key is present
     */
    private void rejectUnrecognisedKeys(String prefix, Set<String> supportedKeys) {
        var unrecognised = new TreeSet<>(environment.getPropertyEntries(prefix));
        unrecognised.addAll(deniedKeysSuppliedAsEnvironmentVariables(prefix, supportedKeys));
        unrecognised.removeAll(supportedKeys);
        if (unrecognised.isEmpty()) {
            return;
        }
        var explained = new StringBuilder();
        for (String key : unrecognised) {
            String explanation = KEYS_FROM_THE_TICKET_SCHEMA.get(key);
            if (explanation != null) {
                explained.append(" '").append(key).append("' ").append(explanation);
            }
        }
        throw new ConfigurationException(
                "Identity provider settings '"
                        + prefix
                        + "' declare unrecognised key(s) "
                        + unrecognised
                        + "; this service reads only "
                        + new TreeSet<>(supportedKeys)
                        + ". An unrecognised key is bound to nothing and silently has no effect."
                        + explained);
    }

    /**
     * Finds keys from the ticket's configuration schema that were supplied as environment variables
     * and would otherwise escape the {@link Environment#getPropertyEntries(String)} sweep.
     *
     * <p>{@code getPropertyEntries} reads only Micronaut's <em>normalized</em> property catalog.
     * When a property arrives as an environment variable, {@code
     * PropertySourcePropertyResolver.processPropertySource} writes every candidate spelling into
     * the <em>generated</em> catalog but only the fully dot-separated one into the normalized
     * catalog. So {@code CONSENT_MANAGER_IDENTITY_PROVIDERS_PRIMARY_JWKS_CACHE_TTL} is resolvable
     * through {@link Environment#containsProperty(String)} at the kebab-cased path, while {@code
     * getPropertyEntries} on the entry prefix never lists it. (Verified against micronaut-inject
     * 5.2.8.)
     *
     * <p>That gap matters because environment variables are the configuration mechanism {@code
     * .env.sample} and {@code application.yml} document as the supported one, and {@code
     * discovery-url} and {@code jwks-cache-ttl} are exactly the keys an operator copies out of the
     * ticket. Without this probe they would bind to nothing and start cleanly — the silent failure
     * this class exists to prevent.
     *
     * <p>Only the keys this service knows it does not read can be probed; an arbitrary misspelling
     * delivered as an environment variable remains invisible, because a direct lookup needs a name
     * to look up.
     *
     * @param prefix the fully-qualified configuration prefix to inspect
     * @param supportedKeys the keys that prefix may declare
     * @return the denied keys that are set at that prefix, possibly empty
     */
    private Set<String> deniedKeysSuppliedAsEnvironmentVariables(
            String prefix, Set<String> supportedKeys) {

        var present = new TreeSet<String>();
        for (String key : KEYS_FROM_THE_TICKET_SCHEMA.keySet()) {
            if (!supportedKeys.contains(key) && environment.containsProperty(prefix + "." + key)) {
                present.add(key);
            }
        }
        return present;
    }

    /**
     * Refuses to start when the unsigned-token guard has been switched off outside a test context.
     *
     * <p>{@link UnsignedTokenRejector} closes an authentication bypass: without it, an {@code alg:
     * none} token authenticates whenever no provider has contributed a signing key — during
     * startup, throughout an identity provider outage, and permanently on a misconfigured issuer.
     * The bean carries a {@code @Requires} switch so an integration test can prove the bypass is
     * real by disabling it and observing the forged token being accepted; without that control case
     * every assertion about the bean would stay green if the bean were deleted.
     *
     * <p>That switch is also readable from a deployment's configuration, which would make an
     * authentication bypass one environment variable away. A Javadoc warning is not a control, so
     * this check turns the switch into a startup failure anywhere {@link Environment#TEST} is not
     * active. The integration test builds its context with the {@code test} environment active and
     * is unaffected; a production deployment that sets the property fails fast instead of failing
     * open.
     *
     * @throws ConfigurationException if the guard is disabled and {@code test} is not among the
     *     active environments
     */
    private void rejectDisabledUnsignedTokenRejector() {
        boolean enabled =
                environment
                        .getProperty(UnsignedTokenRejector.ENABLED_PROPERTY, Boolean.class)
                        .orElse(Boolean.TRUE);
        if (enabled || environment.getActiveNames().contains(Environment.TEST)) {
            return;
        }
        throw new ConfigurationException(
                "'"
                        + UnsignedTokenRejector.ENABLED_PROPERTY
                        + "' is set to false outside a test context. That property removes the"
                        + " only guard against unsigned tokens: with it disabled, a token bearing"
                        + " 'alg: none' authenticates whenever no identity provider has"
                        + " contributed a signing key — during startup, throughout a provider"
                        + " outage, and permanently if the issuer is misconfigured. It exists"
                        + " solely so an integration test can demonstrate that the guard is"
                        + " load-bearing. Remove it from this deployment's configuration.");
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
