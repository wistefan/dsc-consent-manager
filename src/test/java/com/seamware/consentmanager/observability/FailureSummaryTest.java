package com.seamware.consentmanager.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.seamware.consentmanager.support.LogCapture;
import io.micronaut.http.HttpRequest;
import java.sql.SQLException;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

/** Pins that a failure summary names classes and the top frame, never a message, path or query. */
@DisplayName("Failure summary")
class FailureSummaryTest {

    private static final String SENTINEL = "alice@example.com";

    private static final String CHAIN_SEPARATOR = " <- ";

    private static final String QUERY_TOKEN = "eyJhbGciOiJSUzI1NiJ9.query-token-sentinel";

    static Stream<Arguments> failures() {
        Throwable bare = new IllegalStateException(SENTINEL);
        bare.setStackTrace(new StackTraceElement[0]);
        return Stream.of(
                Arguments.of(
                        new RuntimeException(SENTINEL, new SQLException("Key=(" + SENTINEL + ")")),
                        RuntimeException.class.getName()
                                + CHAIN_SEPARATOR
                                + SQLException.class.getName()
                                + " at "
                                + FailureSummaryTest.class.getName()
                                + "."),
                Arguments.of(bare, IllegalStateException.class.getName() + " at <no frame>"),
                Arguments.of(cycle(), FailureSummaryTest.class.getName() + "$"));
    }

    @ParameterizedTest
    @MethodSource("failures")
    @DisplayName("names the class chain and top frame, and never a message")
    void summarisesWithoutMessages(Throwable failure, String expectedPrefix) {
        String summary = FailureSummary.of(failure);

        assertThat(summary).startsWith(expectedPrefix).doesNotContain(SENTINEL);
    }

    @Test
    @DisplayName("summarises no failure as an empty string")
    void summarisesNothing() {
        assertThat(FailureSummary.of(null)).isEmpty();
    }

    @Test
    @DisplayName("names a placeholder, never the path, when no route matched")
    void unmatchedRoute() {
        assertThat(FailureSummary.routeOf(HttpRequest.GET("/users/" + SENTINEL)))
                .isEqualTo(FailureSummary.UNMATCHED_ROUTE);
    }

    @Test
    @DisplayName("keeps the query string out even at DEBUG, since it may carry a token")
    void omitsTheQueryStringAtDebug() {
        Logger log = (Logger) LoggerFactory.getLogger(FailureSummaryTest.class);
        log.setLevel(Level.DEBUG);
        HttpRequest<?> request = HttpRequest.GET("/consents?access_token=" + QUERY_TOKEN);

        try (LogCapture captured = LogCapture.attach()) {
            FailureSummary.logRequestFailure(
                    log, request, "SERVER_ERROR", new IllegalStateException(SENTINEL));

            assertThat(captured.events())
                    .anyMatch(event -> event.getLevel() == Level.DEBUG)
                    .extracting(LogCapture::render)
                    .noneMatch(line -> line.contains(QUERY_TOKEN));
        } finally {
            log.setLevel(null);
        }
    }

    /** Two exceptions each naming the other as cause, which {@code initCause} cannot build. */
    private static Throwable cycle() {
        RuntimeException[] pair = new RuntimeException[2];
        pair[0] =
                new RuntimeException(SENTINEL) {
                    @Override
                    public synchronized Throwable getCause() {
                        return pair[1];
                    }
                };
        pair[1] =
                new RuntimeException(SENTINEL) {
                    @Override
                    public synchronized Throwable getCause() {
                        return pair[0];
                    }
                };
        return pair[0];
    }
}
