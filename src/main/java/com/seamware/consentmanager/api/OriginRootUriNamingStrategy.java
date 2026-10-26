package com.seamware.consentmanager.api;

import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.util.StringUtils;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.web.router.naming.HyphenatedUriNamingStrategy;
import jakarta.inject.Singleton;

/**
 * Route naming that puts controllers under {@code consent-manager.api-prefix}, except an {@link
 * OriginRoot} one. Property-named routes - every management endpoint - stay at the origin root.
 */
@Singleton
@Replaces(HyphenatedUriNamingStrategy.class)
public class OriginRootUriNamingStrategy extends HyphenatedUriNamingStrategy {

    private static final char SEPARATOR = '/';

    private final String apiPrefix;

    public OriginRootUriNamingStrategy(
            @Value("${consent-manager.api-prefix:}") @Nullable String apiPrefix) {
        this.apiPrefix = normalize(apiPrefix);
    }

    @Override
    public String resolveUri(BeanDefinition<?> definition) {
        String uri = super.resolveUri(definition);
        return definition.hasDeclaredAnnotation(OriginRoot.class) ? uri : apiPrefix + uri;
    }

    @Override
    public String resolveUri(Class<?> type) {
        String uri = super.resolveUri(type);
        return type.isAnnotationPresent(OriginRoot.class) ? uri : apiPrefix + uri;
    }

    private static String normalize(@Nullable String prefix) {
        if (StringUtils.isEmpty(prefix)) {
            return StringUtils.EMPTY_STRING;
        }
        String rooted = prefix.charAt(0) == SEPARATOR ? prefix : SEPARATOR + prefix;
        return StringUtils.trimTrailingCharacter(rooted, SEPARATOR);
    }
}
