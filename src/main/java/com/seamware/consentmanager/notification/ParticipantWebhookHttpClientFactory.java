package com.seamware.consentmanager.notification;

import com.seamware.consentmanager.config.ConsentManagerConfiguration.ParticipantNotifications;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.http.client.DefaultHttpClientConfiguration;
import io.micronaut.http.client.RawHttpClient;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

/**
 * Defines the context-owned HTTP client webhooks are posted over. Redirects are off, and that is
 * load-bearing: {@link ParticipantWebhookClient} vets only the registered URL. The client is raw,
 * so a response body is never buffered and a participant cannot make an attempt pull megabytes.
 *
 * <p>Cancelling an abandoned exchange closes a connection already carrying it, but not one still
 * being opened, which Micronaut finishes and pools. The handshake and connection-lifetime caps
 * below are what free such a socket, so a stalling peer cannot accumulate one per attempt.
 */
@Factory
public class ParticipantWebhookHttpClientFactory {

    /** Qualifier of the client bean. */
    public static final String NAME = "participant-webhooks";

    /** The client configuration webhooks are sent with; tests add only a trust store to it. */
    @NonNull
    public static DefaultHttpClientConfiguration configuration(
            @NonNull ParticipantNotifications settings) {
        DefaultHttpClientConfiguration configuration = new DefaultHttpClientConfiguration();
        configuration.setConnectTimeout(settings.getConnectTimeout());
        configuration.setReadTimeout(settings.getReadTimeout());
        configuration.setFollowRedirects(false);
        configuration.getSslConfiguration().setHandshakeTimeout(settings.getConnectTimeout());
        configuration.setConnectTtl(settings.getConnectTimeout().plus(settings.getReadTimeout()));
        return configuration;
    }

    /** The client webhooks are posted over, bounded by the configured timeouts. */
    @Singleton
    @Named(NAME)
    @Bean(preDestroy = "close")
    public RawHttpClient participantWebhookHttpClient(ParticipantNotifications settings) {
        return RawHttpClient.create(null, configuration(settings));
    }
}
