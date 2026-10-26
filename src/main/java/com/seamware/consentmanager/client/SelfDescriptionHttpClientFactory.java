package com.seamware.consentmanager.client;

import com.seamware.consentmanager.config.ConsentManagerConfiguration;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.http.client.DefaultHttpClientConfiguration;
import io.micronaut.http.client.HttpClient;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

/**
 * Defines the context-owned HTTP client self-descriptions are read over; every URL is absolute.
 *
 * <p>Redirects are off, and that is load-bearing: {@link SelfDescriptionClient#permits} vets only
 * the URL handed in, so a followed redirect would reach a destination nothing vetted.
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
