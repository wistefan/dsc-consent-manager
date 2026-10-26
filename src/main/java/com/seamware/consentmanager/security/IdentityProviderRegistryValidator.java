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
import java.util.stream.StreamSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Assembles the trust list at startup and refuses to start when it is absent, malformed or
 * inconsistent, whatever {@code micronaut.security.enabled} says. Eager ({@link Context}) so every
 * entry is created and validated; exposes the list unmodifiable.
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
                    "claims",
                    "role-mapping",
                    "allow-insecure-transport");

    /** Key of the nested block holding the per-provider claim paths. */
    private static final String CLAIMS_KEY = "claims";

    /** Keys a {@code consent-manager.identity-providers.<name>.claims} block may declare. */
    private static final Set<String> SUPPORTED_CLAIM_KEYS =
            Set.of("user-identifier", "participant-identifier", "roles");

    /**
     * Entry-relative keys a provider may declare, in {@link #normaliseKey(String)} form. Excludes
     * {@code role-mapping}, whose keys are role names; see {@link #ROLE_MAPPING_KEY_PREFIX}.
     */
    private static final Set<String> SUPPORTED_ENTRY_KEY_PATHS =
            Set.of(
                    "issuer",
                    "discovery.url",
                    "audience",
                    "clock.skew",
                    "claims.user.identifier",
                    "claims.participant.identifier",
                    "claims.roles",
                    "allow.insecure.transport");

    /** Normalised prefix of {@code role-mapping} keys; the role name is checked elsewhere. */
    private static final String ROLE_MAPPING_KEY_PREFIX = "role.mapping.";

    /** Separator between segments of a normalised configuration key. */
    private static final String KEY_SEPARATOR = ".";

    /** Segment separators across YAML, env-var and nested spellings of a key. */
    private static final Pattern KEY_SEPARATORS = Pattern.compile("[-_.]+");

    /** URL schemes an issuer or a discovery URL may use. */
    private static final Set<String> SUPPORTED_URL_SCHEMES = Set.of("http", "https");

    /** The only scheme that protects the trust anchor in transit; {@code http} needs opt-in. */
    private static final String SECURE_URL_SCHEME = "https";

    /** Key of the per-entry opt-in that tolerates a cleartext provider URL. */
    private static final String ALLOW_INSECURE_TRANSPORT_KEY = "allow-insecure-transport";

    private final Environment environment;

    private final BeanContext beanContext;

    private final List<IdentityProviderConfiguration> providers;

    /** Collects the configured trust list, ordered by name; empty when the block is absent. */
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

    /** The trusted providers by name; unmodifiable and fixed for the life of the process. */
    public List<IdentityProviderConfiguration> getProviders() {
        return providers;
    }

    /**
     * Verifies the assembled trust list during context startup.
     *
     * @throws ConfigurationException on any missing, malformed, duplicate or unknown setting
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
     * Requires every issuer and discovery URL to be an absolute http(s) URL with a host, failing at
     * startup rather than at first discovery.
     */
    private void requireAbsoluteHttpUrls() {
        for (IdentityProviderConfiguration provider : providers) {
            requireAbsoluteHttpUrl(provider.getPropertyPath() + ".issuer", provider.getIssuer());
            requireAbsoluteHttpUrl(
                    provider.getPropertyPath() + ".discovery-url", provider.getDiscoveryUrl());
            requireSecureTransport(provider);
        }
    }

    /**
     * Refuses a cleartext issuer or discovery URL unless the entry opts in, and WARNs on every
     * startup when it does. See {@link
     * IdentityProviderConfiguration#DEFAULT_ALLOW_INSECURE_TRANSPORT}.
     */
    private void requireSecureTransport(IdentityProviderConfiguration provider) {
        List<String> cleartext = new ArrayList<>();
        if (isCleartext(provider.getIssuer())) {
            cleartext.add(provider.getPropertyPath() + ".issuer");
        }
        if (isCleartext(provider.getDiscoveryUrl())) {
            cleartext.add(provider.getPropertyPath() + ".discovery-url");
        }
        if (cleartext.isEmpty()) {
            return;
        }
        if (!provider.isAllowInsecureTransport()) {
            throw new ConfigurationException(
                    cleartext
                            + " use cleartext http. The discovery document is this service's trust"
                            + " anchor: it names the issuer that is verified and the jwks_uri that"
                            + " signing keys are fetched from, so anyone able to rewrite it in"
                            + " transit can make this service accept tokens they minted"
                            + " themselves. Use https, or - for a local provider only - set '"
                            + provider.getPropertyPath()
                            + "."
                            + ALLOW_INSECURE_TRANSPORT_KEY
                            + ": true' to accept that risk explicitly.");
        }
        LOG.warn(
                "Identity provider '{}' is addressed over cleartext http ({}) because '{}.{}' is"
                        + " set. Its discovery document and signing keys are unauthenticated in"
                        + " transit, so anyone on the network path can make this service accept"
                        + " tokens of their own making. This is for local development only.",
                provider.getName(),
                cleartext,
                provider.getPropertyPath(),
                ALLOW_INSECURE_TRANSPORT_KEY);
    }

    /** Whether an already-validated URL uses a scheme other than {@code https}. */
    private static boolean isCleartext(String value) {
        try {
            String scheme = new URI(value).getScheme();
            return scheme != null && !SECURE_URL_SCHEME.equalsIgnoreCase(scheme);
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /** Throws a {@link ConfigurationException} unless the value is an absolute http(s) URL. */
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

    /** Rejects two entries claiming the same issuer, which would make lookup ambiguous. */
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

    /** Rejects an unread entry key; {@code @EachProperty} would silently ignore a misspelling. */
    private void rejectUnrecognisedSettingKeys() {
        for (IdentityProviderConfiguration provider : providers) {
            String prefix = provider.getPropertyPath();
            rejectUnrecognisedKeys(prefix, SUPPORTED_SETTING_KEYS);
            rejectUnrecognisedKeys(prefix + "." + CLAIMS_KEY, SUPPORTED_CLAIM_KEYS);
        }
    }

    /**
     * Rejects any key directly below {@code prefix} outside {@code supportedKeys}. Cannot see
     * env-var keys; {@link #rejectUnrecognisedEnvironmentVariables()} covers those.
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
     * Rejects a trust-list env var that binds to nothing: one naming an undeclared provider (an env
     * var cannot create an entry) or an unread key. Reads raw variable names, not the catalog.
     */
    private void rejectUnrecognisedEnvironmentVariables() {
        String registryPrefix = normaliseKey(IdentityProviderConfiguration.PREFIX) + KEY_SEPARATOR;
        // Longest first, so a provider named `eu` never claims a variable belonging to `eu-gov`.
        List<String> entryPrefixesByLength =
                providers.stream()
                        .sorted(
                                Comparator.comparingInt(
                                                (IdentityProviderConfiguration p) ->
                                                        p.getName().length())
                                        .reversed())
                        .map(provider -> normaliseKey(provider.getPropertyPath()) + KEY_SEPARATOR)
                        .toList();
        for (String variable : environmentVariableNames()) {
            if (normaliseKey(variable).startsWith(registryPrefix)) {
                rejectUnrecognisedEnvironmentVariable(variable, entryPrefixesByLength);
            }
        }
    }

    /** Throws a {@link ConfigurationException} if the variable is bound to nothing. */
    private void rejectUnrecognisedEnvironmentVariable(
            String variable, List<String> entryPrefixesByLength) {
        String normalised = normaliseKey(variable);
        String entryPrefix =
                entryPrefixesByLength.stream()
                        .filter(normalised::startsWith)
                        .findFirst()
                        .orElseThrow(() -> undeclaredProvider(variable));
        String keyPath = normalised.substring(entryPrefix.length());
        if (!isSupportedEntryKeyPath(keyPath)) {
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

    /** Refusal for a registry variable whose provider no configuration block declares. */
    private ConfigurationException undeclaredProvider(String variable) {
        List<String> declared =
                providers.stream().map(IdentityProviderConfiguration::getName).sorted().toList();
        return new ConfigurationException(
                "Environment variable '"
                        + variable
                        + "' addresses an identity provider that is not declared. An environment"
                        + " variable fills in the values of an existing '"
                        + IdentityProviderConfiguration.PREFIX
                        + ".<name>' block; it cannot create one, so this variable is bound to"
                        + " nothing and the provider it names is not trusted. Declared"
                        + " provider(s): "
                        + declared
                        + ".");
    }

    /** The raw environment-variable names, in property-source order. */
    private List<String> environmentVariableNames() {
        return environment.getPropertySources().stream()
                .filter(
                        source ->
                                source.getConvention()
                                        == PropertySource.PropertyConvention.ENVIRONMENT_VARIABLE)
                .flatMap(source -> StreamSupport.stream(source.spliterator(), false))
                .toList();
    }

    /** Whether a normalised entry-relative key path is one this service reads. */
    private static boolean isSupportedEntryKeyPath(String keyPath) {
        return SUPPORTED_ENTRY_KEY_PATHS.contains(keyPath)
                || (keyPath.startsWith(ROLE_MAPPING_KEY_PREFIX)
                        && keyPath.length() > ROLE_MAPPING_KEY_PREFIX.length());
    }

    /** Lower-cases a key and collapses separator runs to {@code .}, so spellings compare equal. */
    private static String normaliseKey(String key) {
        return KEY_SEPARATORS.matcher(key).replaceAll(KEY_SEPARATOR).toLowerCase(Locale.ROOT);
    }

    /**
     * Fails startup when {@link UnsignedTokenRejector} is absent outside {@link Environment#TEST},
     * so its test-only switch cannot open the {@code alg: none} bypass in a deployment. Absence is
     * observed in the bean context, so it cannot drift from the {@code @Requires} that decides it.
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
