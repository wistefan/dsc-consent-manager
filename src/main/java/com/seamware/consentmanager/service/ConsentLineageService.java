package com.seamware.consentmanager.service;

import com.seamware.consentmanager.config.ConsentManagerConfiguration;
import com.seamware.consentmanager.domain.Consent;
import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.domain.User;
import com.seamware.consentmanager.repository.ConsentRepository;
import io.micronaut.core.annotation.NonNull;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.UUID;

/**
 * Reads the chain of consents a record supersedes, newest first, for {@code includeLineage}.
 *
 * <p>Each chain is one recursive query whose every hop is held to the entry point's predicate - the
 * subject's own {@code user_id}, or the participant as provider or consumer - so the chain stops at
 * the first record the caller may not read and that record is never rendered; its id still reaches
 * the caller as the last link's {@code parentConsentId}, as the entry point's own does. The query
 * also stops at the configured depth and at a record already on the path: {@code
 * consents_parent_not_self} forbids a one-row loop but not one across rows.
 */
@Singleton
public class ConsentLineageService {

    private final ConsentRepository consents;

    private final ConsentManagerConfiguration.Consents settings;

    public ConsentLineageService(
            ConsentRepository consents, ConsentManagerConfiguration.Consents settings) {
        this.consents = consents;
        this.settings = settings;
    }

    /** The records {@code consent} supersedes that the subject owns, newest first. */
    @NonNull
    public List<Consent> forUser(@NonNull User subject, @NonNull Consent consent) {
        UUID parentId = consent.getParentConsentId();
        return parentId == null
                ? List.of()
                : consents.findLineageForUser(
                        consent.getId(), parentId, subject.getId(), settings.getLineageMaxDepth());
    }

    /**
     * The records {@code consent} supersedes that name the participant as a party, newest first.
     */
    @NonNull
    public List<Consent> forParticipant(@NonNull Participant caller, @NonNull Consent consent) {
        UUID parentId = consent.getParentConsentId();
        return parentId == null
                ? List.of()
                : consents.findLineageForParticipant(
                        consent.getId(), parentId, caller.getId(), settings.getLineageMaxDepth());
    }
}
