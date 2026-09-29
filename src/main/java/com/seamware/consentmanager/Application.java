package com.seamware.consentmanager;

import io.micronaut.runtime.Micronaut;

/**
 * Main entry point for the Consent Manager application.
 *
 * <p>Starts the Micronaut application context and embedded Netty HTTP server.
 * All configuration is externalized via environment variables; see
 * {@code .env.sample} for documentation of available variables.
 */
public class Application {

    /**
     * Launches the Micronaut application.
     *
     * @param args command-line arguments passed to the Micronaut runtime
     */
    public static void main(String[] args) {
        Micronaut.run(Application.class, args);
    }
}
