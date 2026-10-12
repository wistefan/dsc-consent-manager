package com.seamware.consentmanager.api;

import com.seamware.consentmanager.api.generated.model.PrivacyNotice;
import com.seamware.consentmanager.api.generated.model.PrivacyNoticeControllerDetails;
import com.seamware.consentmanager.api.generated.model.PrivacyNoticeDataInner;
import com.seamware.consentmanager.api.generated.model.PrivacyNoticeRecipientsInner;
import com.seamware.consentmanager.api.generated.model.PrivacyNoticeResolution;
import com.seamware.consentmanager.api.generated.model.ResolutionState;
import com.seamware.consentmanager.domain.PrivacyNoticePayload;
import com.seamware.consentmanager.domain.PrivacyNoticePayload.ControllerDetails;
import com.seamware.consentmanager.domain.PrivacyNoticePayload.DataReference;
import com.seamware.consentmanager.domain.PrivacyNoticePayload.Recipient;
import com.seamware.consentmanager.service.NoticeResolution;
import com.seamware.consentmanager.service.ResolvedNotice;
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
 * block is a parameter for the same reason - generation always reports {@link #unresolved()}, and a
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

    /**
     * A fresh resolution block for a notice nothing was dereferenced for.
     *
     * <p>A new instance per call rather than a shared constant: the generated model is mutable, so
     * a shared one could be aliased into every response and mutated by a single caller.
     */
    @NonNull
    public static PrivacyNoticeResolution unresolved() {
        return new PrivacyNoticeResolution(
                ResolutionState.NOT_REQUESTED,
                ResolutionState.NOT_REQUESTED,
                ResolutionState.NOT_REQUESTED,
                ResolutionState.NOT_REQUESTED);
    }

    /** The stored notice as the API publishes it, under the resolution the caller's request got. */
    @NonNull
    public PrivacyNotice toApi(
            @NonNull com.seamware.consentmanager.domain.PrivacyNotice stored,
            @NonNull String dataProvider,
            @Nullable String dataConsumer,
            @NonNull PrivacyNoticeResolution resolution) {
        return render(
                stored,
                dataProvider,
                dataConsumer,
                stored.getTitle(),
                stored.getPayload(),
                resolution);
    }

    /**
     * A retrieved notice as the API publishes it, with whatever resolution filled in.
     *
     * <p>The title and payload come off the resolved view rather than off the row, because
     * resolution is read-only: the row still holds what generation extracted.
     */
    @NonNull
    public PrivacyNotice toApi(@NonNull ResolvedNotice resolved) {
        return render(
                resolved.notice(),
                resolved.dataProvider(),
                resolved.dataConsumer(),
                resolved.title(),
                resolved.payload(),
                resolution(resolved.resolution()));
    }

    /** The service's per-section report in the model the API publishes it as. */
    @NonNull
    private static PrivacyNoticeResolution resolution(@NonNull NoticeResolution resolution) {
        return new PrivacyNoticeResolution(
                state(resolution.contract()),
                state(resolution.dataResources()),
                state(resolution.purposes()),
                state(resolution.recipients()));
    }

    private static ResolutionState state(NoticeResolution.State state) {
        // Written out rather than matched by name: the two enums are a service record and a
        // generated model, and a compiler error beats a runtime one when either is extended.
        return switch (state) {
            case RESOLVED -> ResolutionState.RESOLVED;
            case UNAVAILABLE -> ResolutionState.UNAVAILABLE;
            case NOT_REQUESTED -> ResolutionState.NOT_REQUESTED;
        };
    }

    /** The one place a notice is rendered, so generation and retrieval cannot publish it apart. */
    @NonNull
    private PrivacyNotice render(
            com.seamware.consentmanager.domain.PrivacyNotice stored,
            String dataProvider,
            @Nullable String dataConsumer,
            @Nullable String title,
            PrivacyNoticePayload payload,
            PrivacyNoticeResolution resolution) {
        PrivacyNotice notice =
                new PrivacyNotice(
                        stored.getId(), stored.getContractUri(), dataProvider, resolution);
        notice.setTitle(title);
        notice.setDataConsumer(dataConsumer);
        notice.setPurposes(payload.purposes());
        notice.setCategoriesOfData(payload.categoriesOfData());
        notice.setData(data(payload.data()));
        notice.setRecipients(recipients(payload.recipients()));
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
                .description(reference.description())
                .partOf(reference.partOf());
    }

    @Nullable
    private static List<PrivacyNoticeRecipientsInner> recipients(
            @Nullable List<Recipient> recipients) {
        return recipients == null
                ? null
                : recipients.stream().map(PrivacyNoticeMapper::recipient).toList();
    }

    private static PrivacyNoticeRecipientsInner recipient(Recipient recipient) {
        return new PrivacyNoticeRecipientsInner()
                .identifier(recipient.identifier())
                .name(recipient.name())
                .contact(recipient.contact())
                .selfDescriptionUri(recipient.selfDescriptionUri());
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
