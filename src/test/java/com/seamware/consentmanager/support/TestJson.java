package com.seamware.consentmanager.support;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.env.Environment;
import io.micronaut.serde.ObjectMapper;
import java.util.Map;

/**
 * The application's own JSON mapper, configured by {@code application.yml}, without a server. One
 * datasource-less context per JVM, so a suite using it stays a unit test.
 */
public final class TestJson {

    private static final ApplicationContext CONTEXT = start();

    private TestJson() {}

    /** The configured mapper. */
    public static ObjectMapper mapper() {
        return CONTEXT.getBean(ObjectMapper.class);
    }

    private static ApplicationContext start() {
        ApplicationContext context =
                ApplicationContext.builder(Environment.TEST)
                        .properties(
                                Map.of(
                                        "datasources.default.enabled", false,
                                        "flyway.enabled", false))
                        .start();
        Runtime.getRuntime().addShutdownHook(new Thread(context::close));
        return context;
    }
}
