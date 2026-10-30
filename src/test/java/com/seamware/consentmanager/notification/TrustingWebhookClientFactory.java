package com.seamware.consentmanager.notification;

import com.seamware.consentmanager.config.ConsentManagerConfiguration.ParticipantNotifications;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.client.DefaultHttpClientConfiguration;
import io.micronaut.http.client.RawHttpClient;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.nio.file.Path;

/**
 * Replaces the webhook client with the production configuration plus trust in a {@link
 * WebhookStubTls} certificate, only in a context given {@link #TRUSTSTORE}.
 */
@Factory
@Requires(property = TrustingWebhookClientFactory.TRUSTSTORE)
class TrustingWebhookClientFactory {

    /** Path of the stub's truststore; set through {@link WebhookStubTls#clientProperties()}. */
    static final String TRUSTSTORE = "test.webhook-stub.truststore";

    @Singleton
    @Named(ParticipantWebhookHttpClientFactory.NAME)
    @Replaces(bean = RawHttpClient.class, named = ParticipantWebhookHttpClientFactory.NAME)
    @Bean(preDestroy = "close")
    RawHttpClient trustingWebhookClient(
            ParticipantNotifications settings, @Property(name = TRUSTSTORE) String truststore) {
        DefaultHttpClientConfiguration configuration =
                ParticipantWebhookHttpClientFactory.configuration(settings);
        configuration
                .getSslConfiguration()
                .setTrustStore(WebhookStubTls.trustStore(Path.of(truststore)));
        return RawHttpClient.create(null, configuration);
    }
}
