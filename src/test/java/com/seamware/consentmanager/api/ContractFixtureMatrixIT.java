package com.seamware.consentmanager.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.support.CatalogStub;
import com.seamware.consentmanager.support.ContractFixtures;
import com.seamware.consentmanager.support.PrivacyNoticeApiTestSupport;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Every Contract Service fixture shape against the notice it yields: generation status, then the
 * section states a resolved read reports. Content is pinned elsewhere; this pins outcomes only.
 */
@MicronautTest(transactional = false)
@DisplayName("Privacy notice outcome per Contract Service fixture")
class ContractFixtureMatrixIT extends PrivacyNoticeApiTestSupport {

    private static final String PATH = "/privacy-notices/";

    private static final String RESOLVED = "RESOLVED";

    private static final String UNAVAILABLE = "UNAVAILABLE";

    /** Read timeout the slow cases outlast, short enough to keep the suite quick. */
    private static final String READ_TIMEOUT = "500ms";

    /** How long a slow endpoint holds the request, comfortably past that timeout. */
    private static final Duration UPSTREAM_DELAY = Duration.ofSeconds(3);

    /** A server error the one-attempt retry budget does not survive. */
    private static final int UPSTREAM_FAILURE = 500;

    /** Catalog root the fixtures name, rewritten to the stub so no case leaves loopback. */
    private static final String FIXTURE_CATALOG = "https://catalog.example.org";

    private static final String BILATERAL_OFFERING_PATH =
            "/v1/catalog/serviceofferings/charging-sessions";

    private static final String ECOSYSTEM_OFFERING_PATH =
            "/v1/catalog/serviceofferings/trip-history";

    private static final String OFFERING_NAME = "Offering";

    private static final String OFFERING_DESCRIPTION = "Data a consenting user shares.";

    private final CatalogStub catalog = new CatalogStub();

    /** One fixture shape and the outcome it must produce; no resolution means no notice. */
    enum ContractCase {
        WELL_FORMED_BILATERAL(
                ContractFixtureMatrixIT::wellFormedBilateral,
                HttpStatus.CREATED,
                states(RESOLVED, RESOLVED, RESOLVED, RESOLVED)),
        WELL_FORMED_ECOSYSTEM(
                ContractFixtureMatrixIT::wellFormedEcosystem,
                HttpStatus.CREATED,
                states(RESOLVED, RESOLVED, RESOLVED, RESOLVED)),
        MISSING_OPTIONAL_SECTIONS(
                ContractFixtureMatrixIT::missingOptionalSections,
                HttpStatus.CREATED,
                states(RESOLVED, RESOLVED, RESOLVED, RESOLVED)),
        UNRESOLVABLE_OFFERING(
                ContractFixtureMatrixIT::unresolvableOffering,
                HttpStatus.CREATED,
                states(RESOLVED, UNAVAILABLE, RESOLVED, RESOLVED)),
        SERVER_ERROR(ContractFixtureMatrixIT::serverError, HttpStatus.BAD_GATEWAY, Map.of()),
        ECOSYSTEM_TIMEOUT(
                ContractFixtureMatrixIT::ecosystemTimeout, HttpStatus.BAD_GATEWAY, Map.of()),
        BILATERAL_TIMEOUT(
                ContractFixtureMatrixIT::bilateralTimeout, HttpStatus.BAD_GATEWAY, Map.of());

        private final BiFunction<ContractFixtureMatrixIT, String, String> stubbing;

        private final HttpStatus status;

        private final Map<String, String> resolution;

        ContractCase(
                BiFunction<ContractFixtureMatrixIT, String, String> stubbing,
                HttpStatus status,
                Map<String, String> resolution) {
            this.stubbing = stubbing;
            this.status = status;
            this.resolution = resolution;
        }

        /** A contract id of its own, as the contract cache outlives no test but spans a class. */
        String contractId() {
            return "matrix-" + name().toLowerCase(Locale.ROOT);
        }
    }

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = new LinkedHashMap<>(super.getProperties());
        properties.put("consent-manager.contract-service.read-timeout", READ_TIMEOUT);
        return properties;
    }

    @AfterEach
    void resetCatalog() {
        catalog.reset();
    }

    @AfterAll
    void stopCatalog() {
        catalog.close();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(ContractCase.class)
    @DisplayName("each fixture shape yields its expected notice outcome")
    void yieldsTheExpectedOutcome(ContractCase contractCase) {
        String contractUri = contractCase.stubbing.apply(this, contractCase.contractId());

        HttpResponse<String> generated =
                post(
                        PATH,
                        Map.of(
                                "contractUri", contractUri,
                                "dataProvider", CALLER,
                                "dataConsumer", CONSUMER));

        assertThat(generated.code()).isEqualTo(contractCase.status.getCode());
        if (contractCase.resolution.isEmpty()) {
            return;
        }
        HttpResponse<String> read = get(PATH + body(generated).get("id"));
        assertThat(read.code()).isEqualTo(HttpStatus.OK.getCode());
        assertThat(resolution(body(read)))
                .containsExactlyInAnyOrderEntriesOf(contractCase.resolution);
    }

    private String wellFormedBilateral(String contractId) {
        catalog.serveOffering(
                BILATERAL_OFFERING_PATH, OFFERING_NAME, OFFERING_DESCRIPTION, List.of());
        contracts.serveBilateralContract(contractId, onStubCatalog(ContractFixtures.BILATERAL));
        return uri(contractId);
    }

    private String wellFormedEcosystem(String contractId) {
        catalog.serveOffering(
                ECOSYSTEM_OFFERING_PATH, OFFERING_NAME, OFFERING_DESCRIPTION, List.of());
        return serveBody(contractId, onStubCatalog(ContractFixtures.ECOSYSTEM));
    }

    private String missingOptionalSections(String contractId) {
        contracts.serveBilateralContract(
                contractId, onStubCatalog(ContractFixtures.BILATERAL_MINIMAL));
        return uri(contractId);
    }

    /** The offering URL is on the stub but answers nothing, so only its section degrades. */
    private String unresolvableOffering(String contractId) {
        return serveBody(contractId, onStubCatalog(ContractFixtures.ECOSYSTEM));
    }

    private String serverError(String contractId) {
        contracts.serveStatus(contractId, UPSTREAM_FAILURE);
        return uri(contractId);
    }

    private String ecosystemTimeout(String contractId) {
        contracts.serveSlowly(contractId, UPSTREAM_DELAY);
        return uri(contractId);
    }

    private String bilateralTimeout(String contractId) {
        contracts.serveBilateralSlowly(contractId, UPSTREAM_DELAY);
        return uri(contractId);
    }

    /** The fixture with the caller as provider and its catalog URLs pointed at the stub. */
    private String onStubCatalog(String fixture) {
        return ContractFixtures.body(fixture)
                .replace(FIXTURE_PROVIDER, CALLER)
                .replace(FIXTURE_CATALOG, catalog.url());
    }

    private static Map<String, String> states(
            String contract, String dataResources, String purposes, String recipients) {
        return Map.of(
                "contract", contract,
                "dataResources", dataResources,
                "purposes", purposes,
                "recipients", recipients);
    }
}
