package com.seamware.consentmanager.api;

import com.seamware.consentmanager.api.generated.model.Consent.StatusEnum;
import com.seamware.consentmanager.api.generated.model.GrantConsent;
import com.seamware.consentmanager.domain.ConsentSnapshot;
import com.seamware.consentmanager.domain.ConsentStatus;
import com.seamware.consentmanager.service.ConsentDetails;
import io.micronaut.core.annotation.NonNull;
import jakarta.inject.Singleton;

/**
 * Translates between the API's consent types and the domain's.
 *
 * <p>{@code purposes} and {@code data} are read off the stored snapshot rather than off the notice:
 * the snapshot is what was elected when the decision was taken, and that is what a consent is
 * evidence of. {@code contractUri} comes off the consent's own column for the same reason.
 *
 * <p>{@code Consent} here is the generated API model; the stored row is named in full to keep the
 * two apart.
 */
@Singleton
public class ConsentMapper {

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
