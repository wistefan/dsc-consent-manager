package com.seamware.consentmanager.config;

import io.micronaut.context.annotation.ConfigurationProperties;

/**
 * Root configuration properties for the Consent Manager application.
 *
 * <p>Binds properties under the {@code consent-manager} prefix from
 * {@code application.yml} (or environment variables) into typed Java fields.
 *
 * <p>Environment variable mapping:
 * <ul>
 *   <li>{@code CONSENT_MANAGER_URL} &rarr; {@link #getUrl()}</li>
 *   <li>{@code CONTRACT_SERVICE_URL} &rarr; {@link #getContractServiceUrl()}</li>
 * </ul>
 *
 * @see <a href="https://docs.micronaut.io/latest/guide/#configurationProperties">
 *      Micronaut Configuration Properties</a>
 */
@ConfigurationProperties("consent-manager")
public class ConsentManagerConfiguration {

    private String url;
    private String contractServiceUrl;

    /**
     * Returns the public base URL of this Consent Manager instance.
     *
     * <p>Bound to the {@code CONSENT_MANAGER_URL} environment variable
     * via the {@code consent-manager.url} configuration key.
     *
     * @return the public base URL (default provided via {@code application.yml})
     */
    public String getUrl() {
        return url;
    }

    /**
     * Sets the public base URL of this Consent Manager instance.
     *
     * @param url the public base URL
     */
    public void setUrl(String url) {
        this.url = url;
    }

    /**
     * Returns the base URL of the Contract Service.
     *
     * <p>Bound to the {@code CONTRACT_SERVICE_URL} environment variable
     * via the {@code consent-manager.contract-service-url} configuration key.
     *
     * @return the Contract Service base URL (default provided via {@code application.yml})
     */
    public String getContractServiceUrl() {
        return contractServiceUrl;
    }

    /**
     * Sets the base URL of the Contract Service.
     *
     * @param contractServiceUrl the Contract Service base URL
     */
    public void setContractServiceUrl(String contractServiceUrl) {
        this.contractServiceUrl = contractServiceUrl;
    }
}
