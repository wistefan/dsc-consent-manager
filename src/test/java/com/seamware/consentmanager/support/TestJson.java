package com.seamware.consentmanager.support;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.env.Environment;
import io.micronaut.serde.ObjectMapper;
import java.util.Map;

/**
 * The application's own JSON mapper, for suites that encode or decode without starting a server.
 *
 * <p>Configured as the application configures it rather than through {@code ObjectMapper.create()},
 * because the serialization inclusion {@code application.yml} sets is exactly what a payload
 * round-trip asserts. The context carries no datasource, so a suite using this stays a unit test,
 * and it is started once per JVM and closed on exit rather than per suite.
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
