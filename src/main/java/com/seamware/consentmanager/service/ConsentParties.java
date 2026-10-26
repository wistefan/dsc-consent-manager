package com.seamware.consentmanager.service;

import com.seamware.consentmanager.domain.Consent;
import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.repository.ParticipantRepository;
import io.micronaut.core.annotation.NonNull;
import jakarta.inject.Singleton;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Resolves a stored consent's two participant foreign keys to the identifiers the API renders. */
@Singleton
public class ConsentParties {

    private static final String UNKNOWN_PARTY =
            "consent %s names participant %s, which no longer exists";

    private final ParticipantRepository participants;

    public ConsentParties(ParticipantRepository participants) {
        this.participants = participants;
    }

    /** The consent with both parties named, reported as a record this call brought into being. */
    @NonNull
    public ConsentDetails details(@NonNull RecordedConsent recorded) {
        return details(recorded.consent(), recorded.created());
    }

    /** Each recorded consent with both parties named, in order; each participant is read once. */
    @NonNull
    public List<ConsentDetails> details(@NonNull List<RecordedConsent> recorded) {
        Map<UUID, String> identifiers = new HashMap<>();
        return recorded.stream()
                .map(
                        written -> {
                            Consent consent = written.consent();
                            return new ConsentDetails(
                                    consent,
                                    identifiers.computeIfAbsent(
                                            consent.getProviderId(),
                                            id -> party(consent, id).getIdentifier()),
                                    identifiers.computeIfAbsent(
                                            consent.getConsumerId(),
                                            id -> party(consent, id).getIdentifier()),
                                    written.created());
                        })
                .toList();
    }

    /** The consent with both parties named; lifecycle moves pass {@code created = false}. */
    @NonNull
    public ConsentDetails details(@NonNull Consent consent, boolean created) {
        return new ConsentDetails(
                consent,
                party(consent, consent.getProviderId()).getIdentifier(),
                party(consent, consent.getConsumerId()).getIdentifier(),
                created);
    }

    /** A participant the consent names; absence is corruption, so it fails loudly, not as 404. */
    private Participant party(Consent consent, UUID participantId) {
        return participants
                .findById(participantId)
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        UNKNOWN_PARTY.formatted(consent.getId(), participantId)));
    }
}
