package com.seamware.consentmanager.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.api.generated.model.ConsentReceipt;
import com.seamware.consentmanager.api.generated.model.ConsentReceiptKantara;
import com.seamware.consentmanager.api.generated.model.ConsentReceiptKantaraPiiControllersInner;
import com.seamware.consentmanager.api.generated.model.ConsentReceiptKantaraPiiControllersInnerAddress;
import com.seamware.consentmanager.api.generated.model.ConsentRepresentation;
import com.seamware.consentmanager.api.generated.model.ConsentResponse;
import com.seamware.consentmanager.support.TestJson;
import io.micronaut.core.type.Argument;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Guards the {@code oneOf} template override, which drops the stock empty-property
 * {@code @JsonTypeInfo}: a representation written as the interface must serialize as its concrete
 * type.
 */
class ConsentRepresentationSerdeTest {

    private static final UUID ID = UUID.fromString("018f3a2b-7c4d-7e8f-9a0b-1c2d3e4f5a6b");
    private static final OffsetDateTime AT =
            OffsetDateTime.of(2026, 1, 2, 3, 4, 5, 0, ZoneOffset.UTC);
    private static final String SUBJECT = "did:example:alice";
    private static final String PROVIDER = "did:example:provider";
    private static final String CONSUMER = "did:example:consumer";
    private static final long EPOCH_SECONDS = AT.toEpochSecond();
    private static final String COLLECTION_METHOD = "API decision";
    private static final String JURISDICTION = "EU";
    private static final String POLICY_URL = "https://contract.example.org/v1/contracts/1";
    private static final String NOT_STATED = "";
    private static final String SIGNATURE = "eyJhbGciOiJFUzI1NiJ9..c2lnbmF0dXJl";

    static Stream<ConsentRepresentation> representations() {
        return Stream.of(
                new ConsentResponse(
                        ID,
                        ID,
                        ConsentResponse.StatusEnum.GRANTED,
                        true,
                        PROVIDER,
                        CONSUMER,
                        AT,
                        AT),
                new ConsentReceipt(
                        ID,
                        ConsentReceipt.StandardEnum.ISO_IEC_29184_2020,
                        ID,
                        ID,
                        SUBJECT,
                        ConsentReceipt.StatusEnum.GRANTED,
                        true,
                        PROVIDER,
                        CONSUMER,
                        AT,
                        AT,
                        SIGNATURE),
                new ConsentReceiptKantara(
                        ConsentReceiptKantara.VersionEnum.KI_CR_V1_1_0,
                        JURISDICTION,
                        EPOCH_SECONDS,
                        COLLECTION_METHOD,
                        ID.toString(),
                        POLICY_URL,
                        SUBJECT,
                        List.of(
                                new ConsentReceiptKantaraPiiControllersInner(
                                        PROVIDER,
                                        PROVIDER,
                                        new ConsentReceiptKantaraPiiControllersInnerAddress(),
                                        NOT_STATED,
                                        NOT_STATED)),
                        List.of(),
                        false,
                        List.of(),
                        SIGNATURE));
    }

    @ParameterizedTest
    @MethodSource("representations")
    void serializesAsTheInterfaceExactlyAsItsConcreteType(ConsentRepresentation value)
            throws IOException {
        Map<String, Object> asInterface = tree(Argument.of(ConsentRepresentation.class), value);
        Map<String, Object> asConcrete = tree(Argument.of(value.getClass()), value);

        assertThat(asInterface).doesNotContainKey("").isEqualTo(asConcrete);
    }

    static Stream<Arguments> versionMarkers() {
        List<ConsentRepresentation> all = representations().toList();
        return Stream.of(
                Arguments.of(all.get(1), "standard", "ISO/IEC 29184:2020"),
                Arguments.of(all.get(2), "version", "KI-CR-v1.1.0"));
    }

    /** An enum whose value is no Java identifier must reach the wire as its value, not its name. */
    @ParameterizedTest
    @MethodSource("versionMarkers")
    void writesAnEnumAsItsValue(ConsentRepresentation value, String property, String wire)
            throws IOException {
        assertThat(tree(Argument.of(ConsentRepresentation.class), value))
                .containsEntry(property, wire);
    }

    @SuppressWarnings("unchecked")
    private static <T> Map<String, Object> tree(Argument<T> type, Object value) throws IOException {
        String json = TestJson.mapper().writeValueAsString(type, (T) value);
        return TestJson.mapper().readValue(json, Argument.mapOf(String.class, Object.class));
    }
}
