package com.seamware.consentmanager.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.security.IdentityProviderRegistry;
import com.seamware.consentmanager.support.Await;
import com.seamware.consentmanager.support.KeycloakTestResource;
import com.seamware.consentmanager.support.PrivacyNoticeApiTestSupport;
import com.seamware.consentmanager.support.PrivacyNoticeFixtures;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves a contract-discovery listing gives up at its configured deadline with {@code 502}, rather
 * than holding the request for as long as a slow Contract Service takes.
 */
@MicronautTest(transactional = false)
class ContractDiscoveryDeadlineIT extends PrivacyNoticeApiTestSupport {

    private static final String PATH_PROVIDER = "/contracts/available/provider";

    private static final String NOTICE_TITLE = "Deadline notice";

    /** Deadline the listing runs under in this suite. */
    private static final Duration MAX_DURATION = Duration.ofSeconds(1);

    /** How long the stubbed contract takes to answer; well past the deadline. */
    private static final Duration UPSTREAM_DELAY = MAX_DURATION.multipliedBy(5);

    /** Longest a cold Keycloak container may take to import its realm and serve discovery. */
    private static final Duration DISCOVERY_TIMEOUT = Duration.ofSeconds(60);

    @Inject IdentityProviderRegistry registry;

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = new LinkedHashMap<>(super.getProperties());
        properties.put(
                "consent-manager.contract-discovery.max-duration", MAX_DURATION.toMillis() + "ms");
        return properties;
    }

    @BeforeEach
    void awaitTheRealm() {
        Await.until(
                "the Keycloak realm to resolve",
                DISCOVERY_TIMEOUT,
                () -> registry.findByIssuer(KeycloakTestResource.issuer()).isPresent());
    }

    @Test
    @DisplayName(
            "a listing outrunning its deadline is 502 with no list, before the upstream answers")
    void slowUpstreamIsBadGatewayAtTheDeadline() {
        String served = serveEcosystem("deadline-a-served");
        String slow = uri("deadline-z-slow");
        contracts.serveSlowly("deadline-z-slow", UPSTREAM_DELAY);
        seed(served, NOTICE_TITLE, CALLER, CONSUMER, PrivacyNoticeFixtures.emptyPayload());
        seed(slow, NOTICE_TITLE, CALLER, CONSUMER, PrivacyNoticeFixtures.emptyPayload());

        long started = System.nanoTime();
        HttpResponse<String> response = get(PATH_PROVIDER);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(response.code()).isEqualTo(HttpStatus.BAD_GATEWAY.getCode());
        assertThat(response.body()).doesNotContain(served);
        assertThat(elapsed)
                .as("the listing answers at its deadline, not when the slow contract does")
                .isLessThan(UPSTREAM_DELAY);
    }
}
