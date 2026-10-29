package com.seamware.consentmanager.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.api.generated.model.BulkRegistrationResult;
import com.seamware.consentmanager.api.generated.model.BulkRegistrationSummary;
import com.seamware.consentmanager.api.generated.model.ConsentReceipt;
import com.seamware.consentmanager.api.generated.model.ConsentReceiptKantara;
import com.seamware.consentmanager.api.generated.model.Participant;
import com.seamware.consentmanager.api.generated.model.ParticipantEndpoints;
import com.seamware.consentmanager.api.generated.model.ParticipantLegalPerson;
import com.seamware.consentmanager.api.generated.model.ParticipantNotification;
import com.seamware.consentmanager.api.generated.model.ParticipantNotificationPage;
import com.seamware.consentmanager.api.generated.model.ParticipantPage;
import com.seamware.consentmanager.api.generated.model.ParticipantRegistration;
import com.seamware.consentmanager.api.generated.model.ParticipantUpdate;
import com.seamware.consentmanager.api.generated.model.PrivacyNotice;
import com.seamware.consentmanager.api.generated.model.PrivacyNoticeResolution;
import com.seamware.consentmanager.api.generated.model.ProblemDetail;
import com.seamware.consentmanager.api.generated.model.ResolutionState;
import com.seamware.consentmanager.api.generated.model.SignedConsentReceipt;
import com.seamware.consentmanager.api.generated.model.SignedConsentReceiptKantara;
import com.seamware.consentmanager.api.generated.model.User;
import com.seamware.consentmanager.api.generated.model.UserRegistrationResult;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URI;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Pins the generated type of every {@code $ref} property that carries its own description.
 *
 * <p>The generator types an {@code allOf: [$ref]} into another component file as {@code Object},
 * which compiles wherever the property is only passed through; the drift costs a build here.
 */
@DisplayName("Generated property types of described references")
class GeneratedPropertyTypeTest {

    static Stream<Arguments> describedReferences() {
        return Stream.of(
                Arguments.of(Participant.class, "legalPerson", ParticipantLegalPerson.class),
                Arguments.of(Participant.class, "endpoints", ParticipantEndpoints.class),
                Arguments.of(
                        ParticipantRegistration.class, "legalPerson", ParticipantLegalPerson.class),
                Arguments.of(
                        ParticipantRegistration.class, "endpoints", ParticipantEndpoints.class),
                Arguments.of(ParticipantUpdate.class, "legalPerson", ParticipantLegalPerson.class),
                Arguments.of(ParticipantUpdate.class, "endpoints", ParticipantEndpoints.class),
                Arguments.of(PrivacyNotice.class, "resolution", PrivacyNoticeResolution.class),
                Arguments.of(PrivacyNoticeResolution.class, "contract", ResolutionState.class),
                Arguments.of(PrivacyNoticeResolution.class, "dataResources", ResolutionState.class),
                Arguments.of(PrivacyNoticeResolution.class, "purposes", ResolutionState.class),
                Arguments.of(PrivacyNoticeResolution.class, "recipients", ResolutionState.class),
                Arguments.of(UserRegistrationResult.class, "user", User.class),
                Arguments.of(
                        BulkRegistrationResult.class, "summary", BulkRegistrationSummary.class),
                Arguments.of(SignedConsentReceipt.class, "receipt", ConsentReceipt.class),
                Arguments.of(
                        SignedConsentReceiptKantara.class, "receipt", ConsentReceiptKantara.class),
                // A uri-reference, which only the pom's type mapping keeps a URI.
                Arguments.of(ProblemDetail.class, "instance", URI.class));
    }

    @ParameterizedTest(name = "{0}.{1} is {2}")
    @MethodSource("describedReferences")
    @DisplayName("keeps its referenced type")
    void keepsReferencedType(Class<?> model, String property, Class<?> expected)
            throws NoSuchFieldException {
        assertThat(model.getDeclaredField(property).getType()).isEqualTo(expected);
    }

    /** Arrays whose items reference a named model, which degrade to {@code List<Object>}. */
    static Stream<Arguments> typedItems() {
        return Stream.of(
                Arguments.of(ParticipantPage.class, "content", Participant.class),
                Arguments.of(
                        ParticipantNotificationPage.class,
                        "content",
                        ParticipantNotification.class));
    }

    @ParameterizedTest(name = "{0}.{1} holds {2}")
    @MethodSource("typedItems")
    @DisplayName("keeps its item type")
    void keepsItemType(Class<?> model, String property, Class<?> expected)
            throws NoSuchFieldException {
        Type type = model.getDeclaredField(property).getGenericType();

        assertThat(type).isInstanceOf(ParameterizedType.class);
        assertThat(((ParameterizedType) type).getActualTypeArguments()).containsExactly(expected);
    }
}
