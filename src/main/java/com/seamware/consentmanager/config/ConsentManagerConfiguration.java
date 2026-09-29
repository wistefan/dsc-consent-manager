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

    /** Default public base URL of this service. */
    private static final String DEFAULT_URL = "http://localhost:8080";

    /** Default base URL of the Contract Service. */
    private static final String DEFAULT_CONTRACT_SERVICE_URL = "http://localhost:8081";

    private String url = DEFAULT_URL;
    private String contractServiceUrl = DEFAULT_CONTRACT_SERVICE_URL;

    /**
     * Returns the public base URL of this Consent Manager instance.
     *
     * <p>Bound to the {@code CONSENT_MANAGER_URL} environment variable
     * via the {@code consent-manager.url} configuration key.
     *
     * @return the public base URL, never {@code null}
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
     * @return the Contract Service base URL, never {@code null}
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
