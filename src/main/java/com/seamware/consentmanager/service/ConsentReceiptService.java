package com.seamware.consentmanager.service;

import com.seamware.consentmanager.api.generated.model.ConsentReceipt;
import com.seamware.consentmanager.api.generated.model.ConsentReceiptControllerDetails;
import com.seamware.consentmanager.api.generated.model.ConsentReceiptDataReferencesInner;
import com.seamware.consentmanager.api.generated.model.ConsentReceiptKantara;
import com.seamware.consentmanager.api.generated.model.ConsentReceiptKantaraPiiControllersInner;
import com.seamware.consentmanager.api.generated.model.ConsentReceiptKantaraPiiControllersInnerAddress;
import com.seamware.consentmanager.api.generated.model.ConsentReceiptKantaraServicesInner;
import com.seamware.consentmanager.api.generated.model.ConsentReceiptKantaraServicesInnerPurposesInner;
import com.seamware.consentmanager.api.generated.model.ConsentReceiptRecipientsInner;
import com.seamware.consentmanager.config.ConsentManagerConfiguration;
import com.seamware.consentmanager.domain.Consent;
import com.seamware.consentmanager.domain.ConsentSnapshot;
import com.seamware.consentmanager.domain.ConsentStatus;
import com.seamware.consentmanager.domain.PrivacyNoticePayload;
import com.seamware.consentmanager.error.BadRequestException;
import com.seamware.consentmanager.repository.UserRepository;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Renders a consent as an ISO/IEC 29184 record or a Kantara KI-CR-v1.1.0 receipt.
 *
 * <p>Every term is read off {@link Consent#getSnapshot()} and {@link Consent#getContractUri()} -
 * never off the live notice - so archiving or regenerating the notice cannot rewrite a receipt.
 */
@Singleton
public class ConsentReceiptService {

    /** How every consent this service holds was collected: the subject's own API decision. */
    static final String COLLECTION_METHOD = "Data subject decision through the Consent Manager API";

    /** Only the data subject grants (ADR-0009), so every consent is explicit. */
    static final String EXPLICIT_CONSENT = "EXPLICIT";

    /** Separator {@code thirdPartyName} joins the notice's recipients with. */
    static final String THIRD_PARTY_SEPARATOR = ", ";

    /** A KI-CR MUST string this service holds no source for: stated empty, never invented. */
    static final String NOT_STATED = "";

    /** {@code policyUrl} of a consent carrying no contract: the notice, as a URN. */
    static final String NOTICE_URN = "urn:uuid:%s";

    /** {@code termination} when the notice stated none: this service's own revocation route. */
    static final String DEFAULT_TERMINATION =
            "Revocable by the data subject at any time via POST /consents/{id}/revoke.";

    /** A notice classifies no PII as sensitive, so no receipt declares any. */
    static final boolean SENSITIVE = false;

    private static final String NOT_GRANTED =
            "Consent '%s' is %s; a Kantara receipt attests a consent that is GRANTED.";

    private static final String MISSING_SUBJECT = "Consent '%s' names no stored data subject.";

    private final UserRepository users;
    private final ConsentManagerConfiguration.Receipts settings;

    public ConsentReceiptService(
            UserRepository users, ConsentManagerConfiguration.Receipts settings) {
        this.users = users;
        this.settings = settings;
    }

    /** The ISO/IEC 29184 record of {@code details}, rendered for a consent in any status. */
    @NonNull
    public ConsentReceipt toIso29184(@NonNull ConsentDetails details) {
        Consent consent = details.consent();
        ConsentSnapshot snapshot = consent.getSnapshot();
        return new ConsentReceipt(
                        consent.getId(),
                        ConsentReceipt.StandardEnum.ISO_IEC_29184_2020,
                        consent.getId(),
                        consent.getPrivacyNoticeId(),
                        subjectIdentifier(consent),
                        ConsentReceipt.StatusEnum.fromValue(consent.getStatus().name()),
                        consent.isConsented(),
                        details.providerIdentifier(),
                        details.consumerIdentifier(),
                        atUtc(consent.getCreatedAt()),
                        atUtc(consent.getUpdatedAt()))
                .contractUri(consent.getContractUri())
                .title(snapshot.title())
                .controllerDetails(controllerDetails(snapshot.controllerDetails()))
                .purposes(snapshot.purposes())
                .dataCategories(snapshot.dataCategories())
                .dataReferences(map(snapshot.dataReferences(), this::dataReference))
                .recipients(map(snapshot.recipients(), this::recipient))
                .retentionPeriod(snapshot.retentionPeriod())
                .piiPrincipalRights(snapshot.piiPrincipalRights())
                .internationalTransfers(snapshot.internationalTransfers())
                .withdrawalOfConsent(snapshot.withdrawalOfConsent());
    }

    /**
     * The KI-CR-v1.1.0 receipt of {@code details}. Throws {@link BadRequestException} unless the
     * consent is {@code GRANTED}: the receipt attests that consent was given.
     */
    @NonNull
    public ConsentReceiptKantara toKantara(@NonNull ConsentDetails details) {
        Consent consent = details.consent();
        if (consent.getStatus() != ConsentStatus.GRANTED) {
            throw new BadRequestException(
                    NOT_GRANTED.formatted(consent.getId(), consent.getStatus()));
        }
        ConsentSnapshot snapshot = consent.getSnapshot();
        return new ConsentReceiptKantara(
                ConsentReceiptKantara.VersionEnum.KI_CR_V1_1_0,
                settings.getJurisdiction(),
                consent.getUpdatedAt().getEpochSecond(),
                COLLECTION_METHOD,
                consent.getId().toString(),
                Objects.requireNonNullElse(
                        consent.getContractUri(),
                        NOTICE_URN.formatted(consent.getPrivacyNoticeId())),
                subjectIdentifier(consent),
                List.of(piiController(snapshot.controllerDetails(), details)),
                List.of(service(snapshot, consent)),
                SENSITIVE,
                List.of());
    }

    /**
     * The subject's identifier; the {@code consents.user_id} FK restricts deletion, so it exists.
     */
    private String subjectIdentifier(Consent consent) {
        return users.findById(consent.getUserId())
                .orElseThrow(
                        () -> new IllegalStateException(MISSING_SUBJECT.formatted(consent.getId())))
                .getIdentifier();
    }

    /** The controller the notice named, or the provider - the controller of its own data. */
    private ConsentReceiptKantaraPiiControllersInner piiController(
            @Nullable PrivacyNoticePayload.ControllerDetails controller, ConsentDetails details) {
        if (controller == null) {
            return controller(details.providerIdentifier(), details.providerIdentifier());
        }
        String name =
                firstPresent(
                        controller.name(), controller.identifier(), details.providerIdentifier());
        return controller(name, Objects.requireNonNullElse(controller.contact(), name))
                .piiControllerUrl(controller.selfDescriptionUri());
    }

    private static ConsentReceiptKantaraPiiControllersInner controller(
            String name, String contact) {
        return new ConsentReceiptKantaraPiiControllersInner(
                name,
                contact,
                new ConsentReceiptKantaraPiiControllersInnerAddress(),
                NOT_STATED,
                NOT_STATED);
    }

    /** The one service a consent covers, named by its notice title, else its contract or notice. */
    private ConsentReceiptKantaraServicesInner service(ConsentSnapshot snapshot, Consent consent) {
        List<String> thirdParties =
                Objects.requireNonNullElse(
                                snapshot.recipients(), List.<PrivacyNoticePayload.Recipient>of())
                        .stream()
                        .flatMap(
                                recipient ->
                                        Stream.of(recipient.name(), recipient.identifier())
                                                .filter(Objects::nonNull)
                                                .limit(1))
                        .toList();
        List<ConsentReceiptKantaraServicesInnerPurposesInner> purposes =
                Objects.requireNonNullElse(snapshot.purposes(), List.<String>of()).stream()
                        .map(
                                purpose ->
                                        new ConsentReceiptKantaraServicesInnerPurposesInner(
                                                        purpose,
                                                        List.of(),
                                                        EXPLICIT_CONSENT,
                                                        Objects.requireNonNullElse(
                                                                snapshot.dataCategories(),
                                                                List.of()),
                                                        Objects.requireNonNullElse(
                                                                snapshot.withdrawalOfConsent(),
                                                                DEFAULT_TERMINATION),
                                                        !thirdParties.isEmpty())
                                                .thirdPartyName(
                                                        thirdParties.isEmpty()
                                                                ? null
                                                                : String.join(
                                                                        THIRD_PARTY_SEPARATOR,
                                                                        thirdParties)))
                        .toList();
        return new ConsentReceiptKantaraServicesInner(
                firstPresent(
                        snapshot.title(),
                        consent.getContractUri(),
                        consent.getPrivacyNoticeId().toString()),
                purposes);
    }

    @Nullable
    private ConsentReceiptControllerDetails controllerDetails(
            @Nullable PrivacyNoticePayload.ControllerDetails controller) {
        return controller == null
                ? null
                : new ConsentReceiptControllerDetails()
                        .name(controller.name())
                        .contact(controller.contact())
                        .identifier(controller.identifier())
                        .selfDescriptionUri(controller.selfDescriptionUri());
    }

    private ConsentReceiptDataReferencesInner dataReference(
            PrivacyNoticePayload.DataReference reference) {
        return new ConsentReceiptDataReferencesInner()
                .uri(reference.uri())
                .title(reference.title())
                .description(reference.description())
                .partOf(reference.partOf());
    }

    private ConsentReceiptRecipientsInner recipient(PrivacyNoticePayload.Recipient recipient) {
        return new ConsentReceiptRecipientsInner()
                .identifier(recipient.identifier())
                .name(recipient.name())
                .contact(recipient.contact())
                .selfDescriptionUri(recipient.selfDescriptionUri());
    }

    /** An absent snapshot list stays absent rather than becoming an empty one. */
    @Nullable
    private static <S, T> List<T> map(@Nullable List<S> source, Function<S, T> mapper) {
        return source == null ? null : source.stream().map(mapper).toList();
    }

    private static String firstPresent(@Nullable String... candidates) {
        return Stream.of(candidates).filter(Objects::nonNull).findFirst().orElseThrow();
    }

    private static OffsetDateTime atUtc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
