package com.seamware.consentmanager.client;

import com.seamware.consentmanager.config.ConsentManagerConfiguration;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.http.client.DefaultHttpClientConfiguration;
import io.micronaut.http.client.HttpClient;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

/**
 * Defines the HTTP client a self-description is read over, as a bean the context owns and closes.
 *
 * <p>A factory rather than a declarative {@code @Client}: every URL this client is given is
 * absolute and comes out of a fetched document, so there is no service to register a base URL
 * against. It is still a singleton bean, so the connection pool is shared and configured in one
 * place rather than built inside the collaborator that uses it.
 *
 * <p>Redirects are off, and that is the load-bearing setting here. {@link
 * SelfDescriptionClient#permits} vets the URL this service was handed; a followed redirect would
 * take the connection somewhere nothing vetted, and both URLs in play come from documents this
 * service does not own, so whoever publishes them would choose the destination.
 */
@Factory
public class SelfDescriptionHttpClientFactory {

    /** Qualifier of the client bean, shared with the cache this client's reads are stored in. */
    public static final String NAME = SelfDescriptionClient.CACHE_NAME;

    /** The client self-descriptions are read over, bounded by the upstream timeouts. */
    @Singleton
    @Named(NAME)
    @Bean(preDestroy = "close")
    public HttpClient selfDescriptionHttpClient(
            ConsentManagerConfiguration.ContractService timeouts) {
        DefaultHttpClientConfiguration configuration = new DefaultHttpClientConfiguration();
        configuration.setConnectTimeout(timeouts.getConnectTimeout());
        configuration.setReadTimeout(timeouts.getReadTimeout());
        configuration.setFollowRedirects(false);
        return HttpClient.create(null, configuration);
    }
}
