package com.seamware.consentmanager.api;

import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.web.router.naming.HyphenatedUriNamingStrategy;
import jakarta.inject.Singleton;

/**
 * Micronaut's route naming, which prefixes every controller with the context path, except an {@link
 * OriginRoot} controller, whose routes it leaves at the origin root.
 */
@Singleton
@Replaces(HyphenatedUriNamingStrategy.class)
public class OriginRootUriNamingStrategy extends HyphenatedUriNamingStrategy {

    private final HyphenatedUriNamingStrategy unprefixed = new HyphenatedUriNamingStrategy();

    public OriginRootUriNamingStrategy(
            @Value("${micronaut.server.context-path}") @Nullable String contextPath) {
        super(contextPath);
    }

    @Override
    public String resolveUri(BeanDefinition<?> definition) {
        return definition.hasDeclaredAnnotation(OriginRoot.class)
                ? unprefixed.resolveUri(definition)
                : super.resolveUri(definition);
    }
}
