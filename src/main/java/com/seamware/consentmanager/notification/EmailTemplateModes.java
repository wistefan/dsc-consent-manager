package com.seamware.consentmanager.notification;

import io.micronaut.context.event.BeanCreatedEvent;
import io.micronaut.context.event.BeanCreatedEventListener;
import jakarta.inject.Singleton;
import java.util.Set;
import org.thymeleaf.templateresolver.AbstractConfigurableTemplateResolver;

/**
 * Renders {@code .html} views in Thymeleaf HTML mode and {@code .txt} views in TEXT mode. Clears
 * the resolver's single suffix, so every view is named with its extension.
 */
@Singleton
class EmailTemplateModes implements BeanCreatedEventListener<AbstractConfigurableTemplateResolver> {

    static final String NO_SUFFIX = "";
    static final String HTML_PATTERN = "*" + NotificationKind.HTML_EXTENSION;
    static final String TEXT_PATTERN = "*" + NotificationKind.TEXT_EXTENSION;

    @Override
    public AbstractConfigurableTemplateResolver onCreated(
            BeanCreatedEvent<AbstractConfigurableTemplateResolver> event) {
        AbstractConfigurableTemplateResolver resolver = event.getBean();
        resolver.setSuffix(NO_SUFFIX);
        resolver.setHtmlTemplateModePatterns(Set.of(HTML_PATTERN));
        resolver.setTextTemplateModePatterns(Set.of(TEXT_PATTERN));
        return resolver;
    }
}
