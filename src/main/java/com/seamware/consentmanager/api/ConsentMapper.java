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
 * Translates between API and domain consent types. Scope comes off the stored snapshot, never the
 * live notice; enums bridge by name, guarded by {@code ConsentEnumParityTest}.
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

    /** The stored consent as a query returns it, with its two parties named globally. */
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

    /** One superseded record as a lineage link. */
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
     * A validity check's outcome; grant fields only when a grant was found, so an unknown subject
     * and one without consent look alike. {@code grantedAt} is the grant's last update.
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

    /** The states a {@code status} filter names, or null for none; an unknown one is a 400. */
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

    /** The body's decision as a status; the spec narrows it to the two a subject can take. */
    @NonNull
    public ConsentStatus toStatus(@NonNull GrantConsent.DecisionEnum decision) {
        return ConsentStatus.valueOf(decision.getValue());
    }
}
