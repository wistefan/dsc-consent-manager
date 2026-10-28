package com.seamware.consentmanager.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.util.Base64URL;
import com.seamware.consentmanager.api.ConsentFormat;
import com.seamware.consentmanager.api.generated.model.ConsentReceiptKantara;
import com.seamware.consentmanager.api.generated.model.ConsentReceiptKantaraPiiControllersInner;
import com.seamware.consentmanager.api.generated.model.ConsentReceiptKantaraServicesInnerPurposesInner;
import com.seamware.consentmanager.api.generated.model.SignedConsentReceiptKantara;
import com.seamware.consentmanager.config.ConsentManagerConfiguration;
import com.seamware.consentmanager.domain.Consent;
import com.seamware.consentmanager.domain.ConsentSnapshot;
import com.seamware.consentmanager.domain.ConsentStatus;
import com.seamware.consentmanager.domain.IssuedReceipt;
import com.seamware.consentmanager.domain.PrivacyNoticePayload.ControllerDetails;
import com.seamware.consentmanager.domain.PrivacyNoticePayload.Recipient;
import com.seamware.consentmanager.domain.User;
import com.seamware.consentmanager.error.BadRequestException;
import com.seamware.consentmanager.repository.IssuedReceiptRepository;
import com.seamware.consentmanager.repository.UserRepository;
import com.seamware.consentmanager.signing.ConsentSigningService;
import com.seamware.consentmanager.signing.SignedReceipt;
import com.seamware.consentmanager.signing.SigningFixtures;
import com.seamware.consentmanager.signing.SigningFixtures.Tamper;
import com.seamware.consentmanager.signing.SigningKeyStore;
import com.seamware.consentmanager.support.ReceiptSignatures;
import com.seamware.consentmanager.support.TestJson;
import io.micronaut.core.type.Argument;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.text.ParseException;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Pins the receipt renderer's fallbacks for a sparsely populated snapshot, and that every key
 * KI-CR-v1.1.0 marks MUST is on the wire however sparse the snapshot is.
 */
@DisplayName("Consent receipt rendering")
class ConsentReceiptServiceTest {

    private static final String SUBJECT = "urn:test:user:receipt-subject";

    private static final String PROVIDER = "urn:test:participant:provider";

    private static final String CONSUMER = "urn:test:participant:consumer";

    private static final String CONTRACT_URI = "https://contract.example.org/v1/contracts/1";

    private static final String TITLE = "Fitness data for research";

    private static final String PURPOSE = "Clinical research";

    private static final String CONTROLLER_NAME = "Fitness Provider Ltd";

    private static final String CONTROLLER_ID = "did:web:provider.example.org";

    private static final String CONTROLLER_CONTACT = "dpo@provider.example.org";

    private static final String RECIPIENT_NAME = "Research Lab";

    private static final String RECIPIENT_ID = "did:web:lab.example.org";

    private static final String OTHER_RECIPIENT_ID = "did:web:archive.example.org";

    /** When the seeded grant was given, a day before the row was last written. */
    private static final Instant GRANTED_AT = Instant.parse("2026-01-01T00:00:00Z");

    /** Top-level keys KI-CR-v1.1.0 marks MUST. */
    private static final Set<String> RECEIPT_MUST =
            Set.of(
                    "version",
                    "jurisdiction",
                    "consentTimestamp",
                    "collectionMethod",
                    "consentReceiptID",
                    "piiPrincipalId",
                    "piiControllers",
                    "policyUrl",
                    "services",
                    "sensitive",
                    "spiCat");

    /** Keys KI-CR-v1.1.0 marks MUST on each {@code piiControllers} entry. */
    private static final Set<String> CONTROLLER_MUST =
            Set.of("piiController", "contact", "address", "email", "phone");

    /** Keys KI-CR-v1.1.0 marks MUST on each {@code services} entry. */
    private static final Set<String> SERVICE_MUST = Set.of("service", "purposes");

    /** Keys KI-CR-v1.1.0 marks MUST on each {@code purposes} entry. */
    private static final Set<String> PURPOSE_MUST =
            Set.of(
                    "purpose",
                    "purposeCategory",
                    "consentType",
                    "piiCategory",
                    "termination",
                    "thirdPartyDisclosure");

    /** Members of a served {@code {receipt, jws}} body. */
    private static final String RECEIPT = "receipt";

    private static final String JWS = "jws";

    /** Splits a compact JWS into its parts. */
    private static final String JWS_PART_SEPARATOR = "\\.";

    private static final int HEADER_PART = 0;

    /** The formats a receipt is served in, crossed with every algorithm and tamper. */
    private static final Set<ConsentFormat> RECEIPT_FORMATS =
            EnumSet.of(ConsentFormat.RECEIPT, ConsentFormat.RECEIPT_KANTARA);

    private static final String CURRENT_KEY_ID = "receipts-current";

    private static final String RETIRED_KEY_ID = "receipts-retired";

    /** Per algorithm, an issuer that also publishes a retired key of that algorithm. */
    private static final Map<JWSAlgorithm, Issuer> ISSUERS = new HashMap<>();

    private final Issuer ephemeral = Issuer.over(SigningFixtures.store(null));

    private final SigningKeyStore keys = ephemeral.keys();

    private final ConsentSigningService signing = ephemeral.signing();

    private final ConsentReceiptService receipts = ephemeral.receipts();

    /** A receipt service signing with {@code keys}, reading back the receipts it issued. */
    private record Issuer(
            SigningKeyStore keys,
            ConsentSigningService signing,
            Map<UUID, IssuedReceipt> stored,
            ConsentReceiptService receipts) {

        static Issuer over(SigningKeyStore keys) {
            ConsentSigningService signing = SigningFixtures.signing(keys);
            Map<UUID, IssuedReceipt> stored = new HashMap<>();
            return new Issuer(
                    keys,
                    signing,
                    stored,
                    new ConsentReceiptService(
                            users(new User(SUBJECT, null, null, null)),
                            issued(stored),
                            new ConsentManagerConfiguration.Receipts(),
                            signing,
                            TestJson.mapper()));
        }

        /**
         * Signs and stores the Kantara receipt of {@code details}, as issuance at the grant does.
         */
        IssuedReceipt issue(ConsentDetails details) {
            SignedReceipt<ConsentReceiptKantara> signed =
                    signing.sign(receipts.toKantara(details, GRANTED_AT));
            IssuedReceipt receipt =
                    new IssuedReceipt(
                            details.consent().getId(),
                            UUID.randomUUID(),
                            signed.canonicalJson(),
                            signed.jws(),
                            signed.keyId(),
                            GRANTED_AT);
            stored.put(receipt.consentId(), receipt);
            return receipt;
        }

        /**
         * The body served for {@code details} in {@code format}, as an outside verifier reads it.
         */
        Map<String, Object> serve(ConsentFormat format, ConsentDetails details) throws IOException {
            Object served =
                    switch (format) {
                        case RECEIPT -> receipts.toIso29184(details);
                        case RECEIPT_KANTARA -> {
                            issue(details);
                            yield receipts.toKantara(details);
                        }
                        case FULL -> throw new IllegalArgumentException(format.name());
                    };
            return TestJson.mapper()
                    .readValue(
                            TestJson.mapper().writeValueAsString(served),
                            Argument.mapOf(String.class, Object.class));
        }
    }

    @BeforeAll
    static void issuePerAlgorithm() throws JOSEException {
        for (JWSAlgorithm algorithm : SigningFixtures.RECEIPT_ALGORITHMS) {
            JWK retired = SigningFixtures.key(algorithm, RETIRED_KEY_ID);
            ISSUERS.put(
                    algorithm,
                    Issuer.over(
                            SigningFixtures.store(
                                    SigningFixtures.key(algorithm, CURRENT_KEY_ID),
                                    new JWKSet(retired.toPublicJWK()).toString())));
        }
    }

    /** A snapshot naming one purpose and nothing else: the sparsest a receipt is drawn from. */
    private static ConsentSnapshot bare() {
        return new ConsentSnapshot(
                null, List.of(PURPOSE), null, null, null, null, null, null, null, null);
    }

    private static ConsentSnapshot withController(ControllerDetails controller) {
        return new ConsentSnapshot(
                null, List.of(PURPOSE), null, null, null, controller, null, null, null, null);
    }

    private static ConsentSnapshot withRecipients(List<Recipient> recipients) {
        return new ConsentSnapshot(
                null, List.of(PURPOSE), null, null, recipients, null, null, null, null, null);
    }

    static Stream<Arguments> controllers() {
        return Stream.of(
                Arguments.of(null, PROVIDER, PROVIDER),
                Arguments.of(new ControllerDetails(null, null, null, null), PROVIDER, PROVIDER),
                Arguments.of(
                        new ControllerDetails(null, null, CONTROLLER_ID, null),
                        CONTROLLER_ID,
                        CONTROLLER_ID),
                Arguments.of(
                        new ControllerDetails(CONTROLLER_NAME, null, CONTROLLER_ID, null),
                        CONTROLLER_NAME,
                        CONTROLLER_NAME),
                Arguments.of(
                        new ControllerDetails(
                                CONTROLLER_NAME, CONTROLLER_CONTACT, CONTROLLER_ID, null),
                        CONTROLLER_NAME,
                        CONTROLLER_CONTACT));
    }

    @ParameterizedTest
    @MethodSource("controllers")
    @DisplayName("names the controller by name, else identifier, else the provider")
    void fallsBackForTheController(ControllerDetails controller, String name, String contact) {
        ConsentReceiptKantaraPiiControllersInner rendered =
                receipts.toKantara(details(withController(controller), CONTRACT_URI), GRANTED_AT)
                        .getPiiControllers()
                        .getFirst();

        assertThat(rendered.getPiiController()).isEqualTo(name);
        assertThat(rendered.getContact()).isEqualTo(contact);
        assertThat(rendered.getEmail()).isEqualTo(ConsentReceiptService.NOT_STATED);
        assertThat(rendered.getPhone()).isEqualTo(ConsentReceiptService.NOT_STATED);
    }

    static Stream<Arguments> serviceNames() {
        return Stream.of(
                Arguments.of(TITLE, CONTRACT_URI, TITLE),
                Arguments.of(TITLE, null, TITLE),
                Arguments.of(null, CONTRACT_URI, CONTRACT_URI),
                Arguments.of(null, null, null));
    }

    @ParameterizedTest
    @MethodSource("serviceNames")
    @DisplayName("names the service by title, else contract, else notice id")
    void fallsBackForTheServiceName(String title, String contractUri, String expected) {
        ConsentSnapshot snapshot =
                new ConsentSnapshot(
                        title, List.of(PURPOSE), null, null, null, null, null, null, null, null);
        ConsentDetails details = details(snapshot, contractUri);

        String service =
                receipts.toKantara(details, GRANTED_AT).getServices().getFirst().getService();

        assertThat(service)
                .isEqualTo(
                        expected == null
                                ? details.consent().getPrivacyNoticeId().toString()
                                : expected);
    }

    static Stream<Arguments> recipients() {
        return Stream.of(
                Arguments.of(null, null),
                Arguments.of(List.of(), null),
                Arguments.of(
                        List.of(new Recipient(RECIPIENT_ID, RECIPIENT_NAME, null, null)),
                        RECIPIENT_NAME),
                Arguments.of(List.of(new Recipient(RECIPIENT_ID, null, null, null)), RECIPIENT_ID),
                Arguments.of(
                        List.of(
                                new Recipient(RECIPIENT_ID, RECIPIENT_NAME, null, null),
                                new Recipient(OTHER_RECIPIENT_ID, null, null, null)),
                        RECIPIENT_NAME
                                + ConsentReceiptService.THIRD_PARTY_SEPARATOR
                                + OTHER_RECIPIENT_ID));
    }

    @ParameterizedTest
    @MethodSource("recipients")
    @DisplayName("discloses to third parties exactly when it names them")
    void namesThirdPartiesExactlyWhenItDisclosesToThem(
            List<Recipient> recipients, String thirdPartyName) {
        ConsentReceiptKantaraServicesInnerPurposesInner purpose =
                receipts.toKantara(details(withRecipients(recipients), CONTRACT_URI), GRANTED_AT)
                        .getServices()
                        .getFirst()
                        .getPurposes()
                        .getFirst();

        assertThat(purpose.getThirdPartyName()).isEqualTo(thirdPartyName);
        assertThat(purpose.getThirdPartyDisclosure()).isEqualTo(thirdPartyName != null);
    }

    @Test
    @DisplayName("states the notice and the service's own terms where the snapshot is silent")
    void fallsBackForPolicyAndTermination() {
        ConsentDetails details = details(bare(), null);

        ConsentReceiptKantara receipt = receipts.toKantara(details, GRANTED_AT);

        assertThat(receipt.getPolicyUrl())
                .isEqualTo(
                        ConsentReceiptService.NOTICE_URN.formatted(
                                details.consent().getPrivacyNoticeId()));
        ConsentReceiptKantaraServicesInnerPurposesInner purpose =
                receipt.getServices().getFirst().getPurposes().getFirst();
        assertThat(purpose.getTermination()).isEqualTo(ConsentReceiptService.DEFAULT_TERMINATION);
        assertThat(purpose.getPiiCategory()).isEmpty();
        assertThat(purpose.getPurposeCategory()).isEmpty();
    }

    @Test
    @DisplayName("publishes every key KI-CR-v1.1.0 marks MUST, even from the sparsest snapshot")
    @SuppressWarnings("unchecked")
    void publishesEveryMustKey() throws IOException {
        String json =
                TestJson.mapper()
                        .writeValueAsString(receipts.toKantara(details(bare(), null), GRANTED_AT));
        Map<String, Object> receipt =
                TestJson.mapper().readValue(json, Argument.mapOf(String.class, Object.class));

        assertThat(receipt).containsKeys(RECEIPT_MUST.toArray(String[]::new));
        Map<String, Object> controller =
                ((List<Map<String, Object>>) receipt.get("piiControllers")).getFirst();
        assertThat(controller).containsKeys(CONTROLLER_MUST.toArray(String[]::new));
        Map<String, Object> service =
                ((List<Map<String, Object>>) receipt.get("services")).getFirst();
        assertThat(service).containsKeys(SERVICE_MUST.toArray(String[]::new));
        assertThat(((List<Map<String, Object>>) service.get("purposes")).getFirst())
                .containsKeys(PURPOSE_MUST.toArray(String[]::new));
    }

    @Test
    @DisplayName("dates a Kantara receipt by the granting instant it is given, not the last write")
    void datesTheReceiptByItsGrantingInstant() {
        ConsentDetails details = details(bare(), CONTRACT_URI);

        assertThat(receipts.toKantara(details, GRANTED_AT).getConsentTimestamp())
                .isEqualTo(GRANTED_AT.getEpochSecond())
                .isNotEqualTo(details.consent().getUpdatedAt().getEpochSecond());
    }

    @Test
    @DisplayName("serves the issued Kantara receipt verbatim, beside its stored signature")
    void servesTheIssuedReceiptAsStored() {
        ConsentDetails details = details(bare(), CONTRACT_URI);
        IssuedReceipt stored = issue(details);

        SignedConsentReceiptKantara served = receipts.toKantara(details);

        assertThat(served.getJws()).isEqualTo(stored.jws());
        assertThat(signing.verifyCanonical(stored.canonicalReceipt(), served.getJws())).isTrue();
        assertThat(signing.verify(served.getReceipt(), served.getJws())).isTrue();
    }

    @Test
    @DisplayName("refuses to serve a granted consent that holds no issued receipt")
    void refusesAGrantWithoutItsReceipt() {
        ConsentDetails details = details(bare(), CONTRACT_URI);

        assertThatThrownBy(() -> receipts.toKantara(details))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @EnumSource(value = ConsentStatus.class, names = "GRANTED", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("refuses a Kantara receipt for a consent that is not granted")
    void refusesAnUngrantedKantaraReceipt(ConsentStatus status) {
        ConsentDetails details = details(status, bare(), CONTRACT_URI);

        assertThatThrownBy(() -> receipts.toKantara(details))
                .isInstanceOf(BadRequestException.class);
    }

    /** Both receipt formats, rendered from the sparsest snapshot. */
    static Stream<Arguments> sparseReceipts() {
        return Stream.of(
                Arguments.of(
                        Named.<Function<ConsentReceiptServiceTest, Object>>of(
                                "ISO/IEC 29184",
                                test -> test.receipts.toIso29184(test.details(bare(), null)))),
                Arguments.of(
                        Named.<Function<ConsentReceiptServiceTest, Object>>of(
                                "Kantara",
                                test -> {
                                    ConsentDetails details = test.details(bare(), null);
                                    test.issue(details);
                                    return test.receipts.toKantara(details);
                                })));
    }

    @ParameterizedTest
    @MethodSource("sparseReceipts")
    @DisplayName("signs exactly the members a sparse receipt serves, so its signature verifies")
    void signsWhatSparseReceiptServes(Function<ConsentReceiptServiceTest, Object> render)
            throws Exception {
        String json = TestJson.mapper().writeValueAsString(render.apply(this));

        assertThat(ReceiptSignatures.verifies(json, new JWKSet(keys.verificationKeys()))).isTrue();
    }

    static Stream<Arguments> formatsAlgorithmsAndTampers() {
        return RECEIPT_FORMATS.stream()
                .flatMap(
                        format ->
                                SigningFixtures.RECEIPT_ALGORITHMS.stream()
                                        .flatMap(
                                                algorithm ->
                                                        Arrays.stream(Tamper.values())
                                                                .map(
                                                                        tamper ->
                                                                                Arguments.of(
                                                                                        format,
                                                                                        algorithm,
                                                                                        tamper))));
    }

    @ParameterizedTest(name = "{0} signed {1}: {2}")
    @MethodSource("formatsAlgorithmsAndTampers")
    @DisplayName("verifies each receipt format under each algorithm, and refuses every tampering")
    @SuppressWarnings("unchecked")
    void refusesTamperingInEveryFormatAndAlgorithm(
            ConsentFormat format, JWSAlgorithm algorithm, Tamper tamper) throws Exception {
        Issuer issuer = ISSUERS.get(algorithm);
        JWKSet published = new JWKSet(issuer.keys().verificationKeys());
        Map<String, Object> served = issuer.serve(format, details(bare(), CONTRACT_URI));
        Map<String, Object> receipt = (Map<String, Object>) served.get(RECEIPT);
        String jws = (String) served.get(JWS);

        assertThat(
                        JWSHeader.parse(Base64URL.from(jws.split(JWS_PART_SEPARATOR)[HEADER_PART]))
                                .getAlgorithm())
                .isEqualTo(algorithm);
        assertThat(ReceiptSignatures.verifies(receipt, jws, published)).isTrue();
        assertThat(issuer.signing().verify(receipt, jws)).isTrue();

        Map<String, Object> presented = tamper.receipt(receipt);
        String presentedJws = tamper.jws(jws, receipt, issuer.keys());
        if (tamper.resigned(issuer.keys())) {
            JWK active = issuer.keys().verificationKey(issuer.keys().keyId()).orElseThrow();
            assertThat(ReceiptSignatures.signedBy(receipt, presentedJws, active))
                    .as("a genuine signature, so only the kid or alg binding refuses it")
                    .isTrue();
        }

        assertThat(issuer.signing().verify(presented, presentedJws)).isFalse();
        assertThat(outsiderAccepts(presented, presentedJws, published)).isFalse();
    }

    /** Whether an outside verifier accepts; a JWS it cannot even parse or check is refused. */
    private static boolean outsiderAccepts(Object receipt, String jws, JWKSet published)
            throws IOException {
        try {
            return ReceiptSignatures.verifies(receipt, jws, published);
        } catch (ParseException | JOSEException e) {
            return false;
        }
    }

    private ConsentDetails details(ConsentSnapshot snapshot, String contractUri) {
        return details(ConsentStatus.GRANTED, snapshot, contractUri);
    }

    /** A consent in {@code status}, last written now. */
    private ConsentDetails details(
            ConsentStatus status, ConsentSnapshot snapshot, String contractUri) {
        Consent consent =
                Consent.withStatus(
                        status,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        null,
                        contractUri,
                        snapshot);
        Instant now = Instant.now();
        consent.setCreatedAt(now);
        consent.setUpdatedAt(now);
        return new ConsentDetails(consent, PROVIDER, CONSUMER, false);
    }

    private IssuedReceipt issue(ConsentDetails details) {
        return ephemeral.issue(details);
    }

    /** An {@link IssuedReceiptRepository} whose {@code findById} reads {@code stored}. */
    private static IssuedReceiptRepository issued(Map<UUID, IssuedReceipt> stored) {
        return (IssuedReceiptRepository)
                Proxy.newProxyInstance(
                        IssuedReceiptRepository.class.getClassLoader(),
                        new Class<?>[] {IssuedReceiptRepository.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("findById")) {
                                return Optional.ofNullable(stored.get(args[0]));
                            }
                            throw new UnsupportedOperationException(method.getName());
                        });
    }

    /** A {@link UserRepository} whose {@code findById} answers {@code subject} for any id. */
    private static UserRepository users(User subject) {
        return (UserRepository)
                Proxy.newProxyInstance(
                        UserRepository.class.getClassLoader(),
                        new Class<?>[] {UserRepository.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("findById")) {
                                return Optional.of(subject);
                            }
                            throw new UnsupportedOperationException(method.getName());
                        });
    }
}
