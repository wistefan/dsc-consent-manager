package com.seamware.consentmanager.api;

import com.seamware.consentmanager.api.generated.model.PrivacyNotice;
import com.seamware.consentmanager.api.generated.model.PrivacyNoticeControllerDetails;
import com.seamware.consentmanager.api.generated.model.PrivacyNoticeDataInner;
import com.seamware.consentmanager.api.generated.model.PrivacyNoticeResolution;
import com.seamware.consentmanager.api.generated.model.ResolutionState;
import com.seamware.consentmanager.domain.PrivacyNoticePayload;
import com.seamware.consentmanager.domain.PrivacyNoticePayload.ControllerDetails;
import com.seamware.consentmanager.domain.PrivacyNoticePayload.DataReference;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import java.util.List;

/**
 * Translates a stored privacy notice into the API's {@code PrivacyNotice} representation.
 *
 * <p>Shared by every operation that returns a notice, so the published shape of a legal document
 * does not drift between generation and retrieval. The parties are passed in rather than read: the
 * row holds participant keys while the representation names global identifiers. The resolution
 * block is a parameter for the same reason - generation always reports {@link #UNRESOLVED}, and a
 * resolved retrieval says how far it got.
 *
 * <p>A section the payload carries no value for is set to {@code null} and dropped by the service's
 * {@code non-absent} serialization: absent is how this API says "no source for this heading".
 *
 * <p>{@code PrivacyNotice} here is the generated API model; the stored row is named in full to keep
 * the two apart.
 */
@Singleton
public class PrivacyNoticeMapper {

    /** The resolution block of a notice nothing was dereferenced for. */
    public static final PrivacyNoticeResolution UNRESOLVED =
            new PrivacyNoticeResolution(
                    ResolutionState.NOT_REQUESTED,
                    ResolutionState.NOT_REQUESTED,
                    ResolutionState.NOT_REQUESTED,
                    ResolutionState.NOT_REQUESTED);

    /** The stored notice as the API publishes it, under the resolution the caller's request got. */
    @NonNull
    public PrivacyNotice toApi(
            @NonNull com.seamware.consentmanager.domain.PrivacyNotice stored,
            @NonNull String dataProvider,
            @Nullable String dataConsumer,
            @NonNull PrivacyNoticeResolution resolution) {
        PrivacyNoticePayload payload = stored.getPayload();
        PrivacyNotice notice =
                new PrivacyNotice(
                        stored.getId(), stored.getContractUri(), dataProvider, resolution);
        notice.setTitle(stored.getTitle());
        notice.setDataConsumer(dataConsumer);
        notice.setPurposes(payload.purposes());
        notice.setCategoriesOfData(payload.categoriesOfData());
        notice.setData(data(payload.data()));
        notice.setRecipients(payload.recipients());
        notice.setControllerDetails(controllerDetails(payload.controllerDetails()));
        notice.setRetentionPeriod(payload.retentionPeriod());
        notice.setPiiPrincipalRights(payload.piiPrincipalRights());
        notice.setInternationalTransfers(payload.internationalTransfers());
        notice.setWithdrawalOfConsent(payload.withdrawalOfConsent());
        notice.setCreatedAt(ParticipantMapper.atUtc(stored.getCreatedAt()));
        notice.setUpdatedAt(ParticipantMapper.atUtc(stored.getUpdatedAt()));
        notice.setArchivedAt(ParticipantMapper.atUtc(stored.getArchivedAt()));
        return notice;
    }

    @Nullable
    private static List<PrivacyNoticeDataInner> data(@Nullable List<DataReference> references) {
        return references == null
                ? null
                : references.stream().map(PrivacyNoticeMapper::entry).toList();
    }

    private static PrivacyNoticeDataInner entry(DataReference reference) {
        return new PrivacyNoticeDataInner()
                .uri(reference.uri())
                .title(reference.title())
                .description(reference.description());
    }

    @Nullable
    private static PrivacyNoticeControllerDetails controllerDetails(
            @Nullable ControllerDetails details) {
        return details == null
                ? null
                : new PrivacyNoticeControllerDetails()
                        .name(details.name())
                        .contact(details.contact())
                        .identifier(details.identifier())
                        .selfDescriptionUri(details.selfDescriptionUri());
    }
}
