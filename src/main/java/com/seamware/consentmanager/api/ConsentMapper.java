package com.seamware.consentmanager.api;

import com.seamware.consentmanager.api.generated.model.Consent.StatusEnum;
import com.seamware.consentmanager.api.generated.model.ConsentLineageLink;
import com.seamware.consentmanager.api.generated.model.ConsentResponse;
import com.seamware.consentmanager.api.generated.model.ConsentValidity;
import com.seamware.consentmanager.api.generated.model.GrantConsent;
import com.seamware.consentmanager.domain.ConsentSnapshot;
import com.seamware.consentmanager.domain.ConsentStatus;
import com.seamware.consentmanager.error.BadRequestException;
import com.seamware.consentmanager.service.ConsentDetails;
import com.seamware.consentmanager.service.ConsentValidityResult;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import java.util.List;

/**
 * Translates between the API's consent types and the domain's.
 *
 * <p>{@code purposes} and {@code data} are read off the stored snapshot rather than off the notice:
 * the snapshot is what was elected when the decision was taken, and that is what a consent is
 * evidence of. {@code contractUri} comes off the consent's own column for the same reason.
 *
 * <p>{@code Consent} here is the generated API model; the stored row is named in full to keep the
 * two apart. Both enum bridges below go by name, which no compiler checks, so {@code
 * ConsentEnumParityTest} fails the build when the served and the stored vocabularies drift.
 */
@Singleton
public class ConsentMapper {

    private static final String UNKNOWN_STATUS = "Unknown consent status '%s'.";

    /** The stored consent as the API publishes it, with its two parties named globally. */
    @NonNull
    public com.seamware.consentmanager.api.generated.model.Consent toRepresentation(
            @NonNull ConsentDetails details) {
        com.seamware.consentmanager.domain.Consent consent = details.consent();
        ConsentSnapshot snapshot = consent.getSnapshot();
        return new com.seamware.consentmanager.api.generated.model.Consent(
                        consent.getId(),
                        consent.getPrivacyNoticeId(),
                        StatusEnum.fromValue(consent.getStatus().name()),
                        consent.isConsented(),
                        details.providerIdentifier(),
                        details.consumerIdentifier(),
                        ParticipantMapper.atUtc(consent.getCreatedAt()),
                        ParticipantMapper.atUtc(consent.getUpdatedAt()))
                .parentConsentId(consent.getParentConsentId())
                .purposes(snapshot.purposes())
                .data(snapshot.dataCategories())
                .contractUri(consent.getContractUri());
    }

    /**
     * The stored consent as a query returns it, with its two parties named globally.
     *
     * <p>{@code purposes} and {@code data} are read off the stored snapshot - the scope elected
     * when the decision was taken - and {@code contractUri} off the consent's own column, so a
     * later regeneration of the notice cannot alter what a query reports. {@code StatusEnum}
     * bridges by name, which {@code ConsentEnumParityTest} guards against drift.
     */
    @NonNull
    public ConsentResponse toResponse(@NonNull ConsentDetails details) {
        com.seamware.consentmanager.domain.Consent consent = details.consent();
        ConsentSnapshot snapshot = consent.getSnapshot();
        return new ConsentResponse(
                        consent.getId(),
                        consent.getPrivacyNoticeId(),
                        ConsentResponse.StatusEnum.fromValue(consent.getStatus().name()),
                        consent.isConsented(),
                        details.providerIdentifier(),
                        details.consumerIdentifier(),
                        ParticipantMapper.atUtc(consent.getCreatedAt()),
                        ParticipantMapper.atUtc(consent.getUpdatedAt()))
                .parentConsentId(consent.getParentConsentId())
                .purposes(snapshot.purposes())
                .data(snapshot.dataCategories())
                .contractUri(consent.getContractUri());
    }

    /** {@link #toResponse(ConsentDetails)} carrying the chain it supersedes, newest first. */
    @NonNull
    public ConsentResponse toResponse(
            @NonNull ConsentDetails details,
            @NonNull List<com.seamware.consentmanager.domain.Consent> lineage) {
        return toResponse(details).lineage(lineage.stream().map(ConsentMapper::toLink).toList());
    }

    /** One superseded record as a lineage link; {@code StatusEnum} bridges by name. */
    private static ConsentLineageLink toLink(com.seamware.consentmanager.domain.Consent consent) {
        return new ConsentLineageLink(
                        consent.getId(),
                        ConsentLineageLink.StatusEnum.fromValue(consent.getStatus().name()),
                        consent.isConsented(),
                        ParticipantMapper.atUtc(consent.getCreatedAt()),
                        ParticipantMapper.atUtc(consent.getUpdatedAt()))
                .parentConsentId(consent.getParentConsentId());
    }

    /**
     * A validity check's outcome as the API publishes it.
     *
     * <p>The grant fields are filled only when a grant was found, so the no-grant answer carries
     * nothing beyond {@code valid}, {@code checkedAt} and its reason - the same shape whether the
     * subject is unknown or merely holds no consent. {@code grantedAt} is the grant's last update,
     * which for a {@code GRANTED} row is when it became the decision it now records.
     */
    @NonNull
    public ConsentValidity toValidity(@NonNull ConsentValidityResult result) {
        ConsentValidity body = new ConsentValidity(result.valid(), result.checkedAt());
        if (result.reason() != null) {
            body.reason(result.reason());
        }
        com.seamware.consentmanager.domain.Consent grant = result.grant();
        if (grant != null) {
            body.consentId(grant.getId())
                    .status(ConsentValidity.StatusEnum.fromValue(grant.getStatus().name()))
                    .grantedAt(ParticipantMapper.atUtc(grant.getUpdatedAt()))
                    .purposes(grant.getSnapshot().purposes());
        }
        return body;
    }

    /**
     * The lifecycle states a {@code status} query filter names, or null when it named none.
     *
     * <p>The query carries raw strings - the generated parameter is unconstrained - so an
     * unrecognised state is refused here as a {@code 400} rather than silently dropped.
     */
    @Nullable
    public List<ConsentStatus> toStatuses(@Nullable List<String> statuses) {
        return statuses == null ? null : statuses.stream().map(ConsentMapper::toStatus).toList();
    }

    private static ConsentStatus toStatus(String value) {
        try {
            return ConsentStatus.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(UNKNOWN_STATUS.formatted(value), e);
        }
    }

    /**
     * The decision a request body states, as the lifecycle names it.
     *
     * <p>The enum is narrowed to the two decisions a subject can take in the specification, so this
     * cannot widen into a status only a lifecycle move produces.
     */
    @NonNull
    public ConsentStatus toStatus(@NonNull GrantConsent.DecisionEnum decision) {
        return ConsentStatus.valueOf(decision.getValue());
    }
}
