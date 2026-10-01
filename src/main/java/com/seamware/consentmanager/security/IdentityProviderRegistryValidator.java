package com.seamware.consentmanager.security;

import io.micronaut.context.BeanContext;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.env.Environment;
import io.micronaut.context.env.PropertySource;
import io.micronaut.context.exceptions.ConfigurationException;
import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Assembles the identity provider trust list at startup and refuses to start when it is missing,
 * malformed or internally inconsistent.
 *
 * <p>A trusted provider is described by a single block, {@code
 * consent-manager.identity-providers.<name>}, bound to one {@link IdentityProviderConfiguration}
 * bean. Bean Validation on those beans can only reject a <em>malformed</em> entry; it cannot notice
 * that the list is <em>absent</em>, because an absent list simply produces no beans. This {@link
 * Context}-scoped bean closes that gap: it is instantiated eagerly during context startup, which
 * both forces every trust-list entry to be created (and therefore validated) and lets it assert
 * that at least one entry exists.
 *
 * <p>The check is <strong>unconditional</strong>. It fires regardless of {@code
 * micronaut.security.enabled}, because a resource server with no trust list is misconfigured
 * whatever that toggle says, and a deployment that discovers the problem at the first authenticated
 * request rather than at startup has already failed silently.
 *
 * <p>Per the immutability rule for the trust list, this bean exposes an unmodifiable view of the
 * configured providers and offers no way to add, remove, replace or reload an entry.
 *
 * <p><strong>Interim posture until the token validator lands.</strong> This step delivers the trust
 * list only; discovery (step 3), key resolution (step 4) and the per-issuer token validator (step
 * 5) are still to come. No signing key is therefore registered with Micronaut Security yet, so
 * <em>every</em> bearer token is rejected and no request can authenticate - the fail-closed
 * direction. {@link UnsignedTokenRejector} keeps that true for {@code alg: none} tokens as well,
 * which the framework would otherwise treat as validly signed precisely because no signature
 * configuration exists; see {@link #rejectDisabledUnsignedTokenRejector()}.
 *
 * @see IdentityProviderConfiguration
 */
@Context
public class IdentityProviderRegistryValidator {

    private static final Logger LOG =
            LoggerFactory.getLogger(IdentityProviderRegistryValidator.class);

    /** Keys a {@code consent-manager.identity-providers.<name>} entry may declare. */
    private static final Set<String> SUPPORTED_SETTING_KEYS =
            Set.of(
                    "issuer",
                    "discovery-url",
                    "audience",
                    "clock-skew",
                    "jwks-cache-ttl",
                    "claims",
                    "role-mapping");

    /** Key of the nested block holding the per-provider claim paths. */
    private static final String CLAIMS_KEY = "claims";

    /** Keys a {@code consent-manager.identity-providers.<name>.claims} block may declare. */
    private static final Set<String> SUPPORTED_CLAIM_KEYS =
            Set.of("user-identifier", "participant-identifier", "roles");

    /**
     * Fully-qualified keys, relative to one entry, that a provider may declare.
     *
     * <p>Written in the <em>normalised</em> form produced by {@link #normaliseKey(String)} so a key
     * can be recognised whichever spelling it arrived in. {@code role-mapping} is absent because
     * its keys are role names rather than a fixed set; see {@link #ROLE_MAPPING_KEY_PREFIX}.
     */
    private static final Set<String> SUPPORTED_ENTRY_KEY_PATHS =
            Set.of(
                    "issuer",
                    "discovery.url",
                    "audience",
                    "clock.skew",
                    "jwks.cache.ttl",
                    "claims.user.identifier",
                    "claims.participant.identifier",
                    "claims.roles");

    /**
     * Normalised prefix of every key below a provider's {@code role-mapping} block.
     *
     * <p>What follows it is a role name, which {@link IdentityProviderConfiguration#validate()}
     * checks against {@link Role}, so this class only establishes that the key belongs to that
     * block at all.
     */
    private static final String ROLE_MAPPING_KEY_PREFIX = "role.mapping.";

    /** Separator between segments of a normalised configuration key. */
    private static final String KEY_SEPARATOR = ".";

    /**
     * Every run of characters that separates segments in some spelling of a configuration key.
     *
     * <p>YAML writes {@code jwks-cache-ttl}, an environment variable writes {@code JWKS_CACHE_TTL},
     * and a nested block adds a {@code .}. Collapsing all three to one separator makes the
     * spellings comparable.
     */
    private static final Pattern KEY_SEPARATORS = Pattern.compile("[-_.]+");

    /**
     * URL schemes an issuer or a discovery URL may use.
     *
     * <p>Both identify an endpoint whose metadata and signing keys are fetched over the network, so
     * only the two HTTP schemes are meaningful. Anything else is a configuration mistake.
     */
    private static final Set<String> SUPPORTED_URL_SCHEMES = Set.of("http", "https");

    private final Environment environment;

    private final BeanContext beanContext;

    private final List<IdentityProviderConfiguration> providers;

    /**
     * Collects the configured trust list in a stable order.
     *
     * @param environment the resolved configuration, used to detect keys that bind to nothing and
     *     to read the active environment names
     * @param beanContext the bean context, used to observe whether the unsigned-token guard is
     *     actually registered rather than re-deriving that from its switch
     * @param providers one bean per {@code consent-manager.identity-providers} entry, injected by
     *     Micronaut; empty when the block is absent
     */
    public IdentityProviderRegistryValidator(
            Environment environment,
            BeanContext beanContext,
            List<IdentityProviderConfiguration> providers) {
        this.environment = environment;
        this.beanContext = beanContext;
        var ordered = new ArrayList<>(providers);
        ordered.sort(Comparator.comparing(IdentityProviderConfiguration::getName));
        this.providers = List.copyOf(ordered);
    }

    /**
     * Returns the configured trust list.
     *
     * <p>The returned collection is unmodifiable and is fixed for the lifetime of the process.
     *
     * @return the trusted providers, ordered by name
     */
    public List<IdentityProviderConfiguration> getProviders() {
        return providers;
    }

    /**
     * Verifies the assembled trust list during context startup.
     *
     * @throws ConfigurationException if no provider is configured, an issuer or discovery URL is
     *     not an absolute http(s) URL, two providers declare the same issuer, an entry declares an
     *     unrecognised key, an environment variable names an undeclared provider or an unrecognised
     *     key, or the unsigned-token guard is switched off outside a test context
     */
    @PostConstruct
    public void validate() {
        if (providers.isEmpty()) {
            throw new ConfigurationException(
                    "No identity provider is configured. The Consent Manager is an OAuth2 resource"
                            + " server and cannot validate any token without a trust list;"
                            + " configure at least one provider under '"
                            + IdentityProviderConfiguration.PREFIX
                            + ".<name>' with its 'issuer', 'discovery-url' and 'audience'.");
        }
        requireAbsoluteHttpUrls();
        rejectDuplicateIssuers();
        rejectUnrecognisedSettingKeys();
        rejectUnrecognisedEnvironmentVariables();
        rejectDisabledUnsignedTokenRejector();
        LOG.info(
                "Identity provider trust list loaded with {} provider(s): {}",
                providers.size(),
                providers.stream().map(p -> p.getName() + " -> " + p.getIssuer()).toList());
    }

    /**
     * Asserts that every issuer and discovery URL is an absolute {@code http} or {@code https} URL
     * with a host.
     *
     * <p>Bean Validation only establishes that the two values are present and non-blank. A value
     * that is present but not a URL would fail much later - at the first discovery attempt, or
     * never, if the issuer is simply never matched - so it is rejected here instead.
     *
     * @throws ConfigurationException if either value is not such a URL
     */
    private void requireAbsoluteHttpUrls() {
        for (IdentityProviderConfiguration provider : providers) {
            requireAbsoluteHttpUrl(provider.getPropertyPath() + ".issuer", provider.getIssuer());
            requireAbsoluteHttpUrl(
                    provider.getPropertyPath() + ".discovery-url", provider.getDiscoveryUrl());
        }
    }

    /**
     * Asserts that a configured value is an absolute {@code http} or {@code https} URL with a host.
     *
     * @param property the fully-qualified property name, used in the failure message
     * @param value the raw configured value
     * @throws ConfigurationException if the value is not such a URL
     */
    private void requireAbsoluteHttpUrl(String property, String value) {
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            throw new ConfigurationException(property + " is not a valid URL: '" + value + "'", e);
        }
        String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
        if (scheme == null || !SUPPORTED_URL_SCHEMES.contains(scheme) || uri.getHost() == null) {
            throw new ConfigurationException(
                    property
                            + " must be an absolute "
                            + new TreeSet<>(SUPPORTED_URL_SCHEMES)
                            + " URL with a host, but was '"
                            + value
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
        for (IdentityProviderConfiguration provider : providers) {
            String previousName = firstSeenAs.putIfAbsent(provider.getIssuer(), provider.getName());
            if (previousName != null) {
                throw new ConfigurationException(
                        IdentityProviderConfiguration.PREFIX
                                + " declares issuer '"
                                + provider.getIssuer()
                                + "' for both '"
                                + previousName
                                + "' and '"
                                + provider.getName()
                                + "'; each issuer must appear exactly once, because a token is"
                                + " matched to its provider by issuer alone.");
            }
        }
    }

    /**
     * Rejects an entry that declares a key this service does not read.
     *
     * <p>{@code @EachProperty} binds the keys it knows and ignores the rest in silence, so a
     * misspelled {@code audiance} would leave the audience unset rather than fail. On the security
     * boundary that is the worst possible outcome, so an unknown key aborts startup.
     *
     * @throws ConfigurationException if an entry declares a key outside {@link
     *     #SUPPORTED_SETTING_KEYS}, or its {@code claims} block declares one outside {@link
     *     #SUPPORTED_CLAIM_KEYS}
     */
    private void rejectUnrecognisedSettingKeys() {
        for (IdentityProviderConfiguration provider : providers) {
            String prefix = provider.getPropertyPath();
            rejectUnrecognisedKeys(prefix, SUPPORTED_SETTING_KEYS);
            rejectUnrecognisedKeys(prefix + "." + CLAIMS_KEY, SUPPORTED_CLAIM_KEYS);
        }
    }

    /**
     * Asserts that every configured key directly below a prefix is one this service reads.
     *
     * <p>{@link Environment#getPropertyEntries(String)} reads Micronaut's <em>normalized</em>
     * property catalog, which lists every key declared in YAML or passed as a context property.
     * Verified against micronaut-inject 5.2.8, a property that arrives as an environment variable
     * is written in full only to the <em>generated</em> catalog and is therefore not enumerable
     * here. That path is covered separately by {@link #rejectUnrecognisedEnvironmentVariables()},
     * which reads the raw variable names instead of the catalog.
     *
     * @param prefix the fully-qualified configuration prefix to inspect
     * @param supportedKeys the keys that prefix may declare
     * @throws ConfigurationException if any other key is present
     */
    private void rejectUnrecognisedKeys(String prefix, Set<String> supportedKeys) {
        var unrecognised = new TreeSet<>(environment.getPropertyEntries(prefix));
        unrecognised.removeAll(supportedKeys);
        if (unrecognised.isEmpty()) {
            return;
        }
        throw new ConfigurationException(
                "Identity provider settings '"
                        + prefix
                        + "' declare unrecognised key(s) "
                        + unrecognised
                        + "; this service reads only "
                        + new TreeSet<>(supportedKeys)
                        + ". An unrecognised key is bound to nothing and silently has no effect.");
    }

    /**
     * Rejects an environment variable that addresses the trust list but changes nothing.
     *
     * <p>{@link #rejectUnrecognisedKeys(String, Set)} cannot see these: verified against
     * micronaut-inject 5.2.8, {@code PropertySourcePropertyResolver} writes the fully expanded
     * spelling of an environment variable only into the <em>generated</em> property catalog, while
     * {@link Environment#getPropertyEntries(String)} enumerates the <em>normalized</em> one. A
     * direct lookup also cannot help, because a misspelling has no name to look up. So this check
     * works from the other end: it reads the raw variable names out of the environment-variable
     * property sources and matches each one against the schema.
     *
     * <p>Two things are refused, both of which would otherwise leave a resource server running with
     * a trust list its operator does not have:
     *
     * <ul>
     *   <li>a variable naming a provider that no configuration block declares -
     *       {@code @EachProperty} does not create a bean from an environment variable alone, so the
     *       intended provider is simply absent from the trust list;
     *   <li>a variable naming a key below a declared provider that this service does not read -
     *       typically a misspelling, which binds to nothing and leaves the real setting at its
     *       default.
     * </ul>
     *
     * @throws ConfigurationException if such a variable is set
     */
    private void rejectUnrecognisedEnvironmentVariables() {
        String registryPrefix = normaliseKey(IdentityProviderConfiguration.PREFIX) + KEY_SEPARATOR;
        // Longest first, so a provider named `eu` never claims a variable belonging to `eu-gov`.
        List<IdentityProviderConfiguration> byPrefixLength = new ArrayList<>(providers);
        byPrefixLength.sort(
                Comparator.comparingInt((IdentityProviderConfiguration p) -> p.getName().length())
                        .reversed());

        for (String variable : environmentVariableNames()) {
            String normalised = normaliseKey(variable);
            if (!normalised.startsWith(registryPrefix)) {
                continue;
            }
            String entryPrefix = null;
            for (IdentityProviderConfiguration provider : byPrefixLength) {
                String candidate = normaliseKey(provider.getPropertyPath()) + KEY_SEPARATOR;
                if (normalised.startsWith(candidate)) {
                    entryPrefix = candidate;
                    break;
                }
            }
            if (entryPrefix == null) {
                throw new ConfigurationException(
                        "Environment variable '"
                                + variable
                                + "' addresses an identity provider that is not declared. An"
                                + " environment variable fills in the values of an existing '"
                                + IdentityProviderConfiguration.PREFIX
                                + ".<name>' block; it cannot create one, so this variable is bound"
                                + " to nothing and the provider it names is not trusted. Declared"
                                + " provider(s): "
                                + providers.stream()
                                        .map(IdentityProviderConfiguration::getName)
                                        .sorted()
                                        .toList()
                                + ".");
            }
            String keyPath = normalised.substring(entryPrefix.length());
            if (isSupportedEntryKeyPath(keyPath)) {
                continue;
            }
            throw new ConfigurationException(
                    "Environment variable '"
                            + variable
                            + "' declares unrecognised key(s) ["
                            + keyPath
                            + "]; this service reads only "
                            + new TreeSet<>(SUPPORTED_ENTRY_KEY_PATHS)
                            + " and '"
                            + ROLE_MAPPING_KEY_PREFIX
                            + "<role>'. An unrecognised key is bound to nothing and silently has no"
                            + " effect.");
        }
    }

    /**
     * Returns the raw names of every variable supplied the way the operating system supplies
     * environment variables.
     *
     * @return the raw variable names, in property-source order
     */
    private List<String> environmentVariableNames() {
        List<String> names = new ArrayList<>();
        for (PropertySource source : environment.getPropertySources()) {
            if (source.getConvention() == PropertySource.PropertyConvention.ENVIRONMENT_VARIABLE) {
                source.forEach(names::add);
            }
        }
        return names;
    }

    /**
     * Reports whether a key path below a provider entry is one this service reads.
     *
     * @param keyPath the normalised key path, relative to the entry
     * @return {@code true} if the key belongs to the documented schema
     */
    private static boolean isSupportedEntryKeyPath(String keyPath) {
        return SUPPORTED_ENTRY_KEY_PATHS.contains(keyPath)
                || (keyPath.startsWith(ROLE_MAPPING_KEY_PREFIX)
                        && keyPath.length() > ROLE_MAPPING_KEY_PREFIX.length());
    }

    /**
     * Reduces a configuration key to a spelling-independent form.
     *
     * <p>Every run of {@code -}, {@code _} and {@code .} collapses to a single {@code .} and the
     * result is lower-cased, so {@code JWKS_CACHE_TTL}, {@code jwks-cache-ttl} and {@code
     * jwks.cache.ttl} all become {@code jwks.cache.ttl}. Segment boundaries are preserved, so one
     * provider's keys can never be mistaken for a longer-named provider's.
     *
     * @param key a configuration key or environment variable name
     * @return the normalised form
     */
    private static String normaliseKey(String key) {
        return KEY_SEPARATORS.matcher(key).replaceAll(KEY_SEPARATOR).toLowerCase(Locale.ROOT);
    }

    /**
     * Refuses to start when the unsigned-token guard has been switched off outside a test context.
     *
     * <p>{@link UnsignedTokenRejector} closes an authentication bypass: without it, an {@code alg:
     * none} token authenticates whenever no signature configuration is registered - which, until
     * the per-issuer validator lands in step 5, is always. The bean carries a {@code @Requires}
     * switch so an integration test can prove the bypass is real by disabling it and observing the
     * forged token being accepted; without that control case every assertion about the bean would
     * stay green if the bean were deleted as apparently dead code.
     *
     * <p>That switch is also readable from a deployment's configuration, which would make an
     * authentication bypass one environment variable away. A Javadoc warning is not a control, so
     * this check turns the switch into a startup failure anywhere {@link Environment#TEST} is not
     * active. The integration test builds its context with the {@code test} environment active and
     * is unaffected; a production deployment that sets the property fails fast instead of failing
     * open.
     *
     * <p>The guard's absence is <em>observed</em> through the bean context rather than re-derived
     * by reading {@link UnsignedTokenRejector#ENABLED_PROPERTY} back. The {@code @Requires} that
     * decides the bean's fate compares the property to {@code "false"} as an exact, case-sensitive
     * string, while a {@code Boolean} conversion here would also treat {@code "FALSE"} as disabled
     * - so the two could disagree and report a guard removed that is in fact registered. Asking the
     * context which beans exist cannot drift from the annotation that created them.
     *
     * @throws ConfigurationException if the guard is not registered and {@code test} is not among
     *     the active environments
     */
    private void rejectDisabledUnsignedTokenRejector() {
        if (beanContext.findBean(UnsignedTokenRejector.class).isPresent()
                || environment.getActiveNames().contains(Environment.TEST)) {
            return;
        }
        throw new ConfigurationException(
                "The unsigned-token guard is not registered outside a test context, which means '"
                        + UnsignedTokenRejector.ENABLED_PROPERTY
                        + "' has been set to false. That property removes the only guard against"
                        + " unsigned tokens: with it disabled, a token bearing 'alg: none'"
                        + " authenticates whenever no signature configuration is registered. It"
                        + " exists solely so an integration test can demonstrate that the guard is"
                        + " load-bearing. Remove it from this deployment's configuration.");
    }
}
