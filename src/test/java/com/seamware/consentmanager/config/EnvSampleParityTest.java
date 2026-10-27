package com.seamware.consentmanager.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;

/**
 * Pins that every environment variable the service reads is documented in {@code .env.sample}, and
 * that the obsolete auth settings of the previous implementation are not.
 */
@DisplayName(".env.sample parity")
class EnvSampleParityTest {

    private static final Path APPLICATION_YML = Path.of("src/main/resources/application.yml");

    private static final Path ENV_SAMPLE = Path.of(".env.sample");

    /** An upper-snake placeholder, {@code ${NAME}} or {@code ${NAME:default}}; dotted keys skip. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Z][A-Z0-9_]*)[:}]");

    /** A documented assignment in {@code .env.sample}, commented out or not. */
    private static final String DOCUMENTED_FORMAT = "(?m)^#?\\s*%s=";

    /**
     * Bound straight from the environment by {@code NotificationConfiguration}, never a
     * placeholder.
     */
    static final List<String> ENVIRONMENT_ONLY_VARIABLES =
            List.of("SMTP_HOST", "SMTP_PORT", "SMTP_STARTTLS");

    /** Settings of the session-based predecessor; this resource server reads none of them. */
    static final List<String> OBSOLETE_VARIABLES =
            List.of(
                    "JWT_SECRET_KEY",
                    "OAUTH_SECRET_KEY",
                    "SESSION_SECRET",
                    "SALT_ROUNDS",
                    "X_CATALOG_CONSENT_KEY");

    private static String envSample;

    @BeforeAll
    static void readEnvSample() throws IOException {
        envSample = Files.readString(ENV_SAMPLE, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("documents every variable application.yml reads")
    void documentsEveryPlaceholder() throws IOException {
        Set<String> read = new TreeSet<>();
        Matcher placeholder =
                PLACEHOLDER.matcher(Files.readString(APPLICATION_YML, StandardCharsets.UTF_8));
        while (placeholder.find()) {
            read.add(placeholder.group(1));
        }

        assertThat(read).isNotEmpty();
        assertThat(read).allMatch(EnvSampleParityTest::documented, "documented in .env.sample");
    }

    @ParameterizedTest
    @FieldSource("ENVIRONMENT_ONLY_VARIABLES")
    @DisplayName("documents a variable read only from the environment")
    void documentsEnvironmentOnlyVariable(String variable) {
        assertThat(documented(variable)).as(variable).isTrue();
    }

    @ParameterizedTest
    @FieldSource("OBSOLETE_VARIABLES")
    @DisplayName("does not mention an obsolete auth setting")
    void omitsObsoleteVariable(String variable) {
        assertThat(envSample).doesNotContain(variable);
    }

    private static boolean documented(String variable) {
        return Pattern.compile(DOCUMENTED_FORMAT.formatted(Pattern.quote(variable)))
                .matcher(envSample)
                .find();
    }
}
