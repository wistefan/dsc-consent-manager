package com.seamware.consentmanager;

import io.micronaut.runtime.Micronaut;

/** Consent Manager entry point; configuration comes from env vars documented in .env.sample. */
public class Application {

    /** Starts the Micronaut context and the embedded Netty server. */
    public static void main(String[] args) {
        Micronaut.run(Application.class, args);
    }
}
