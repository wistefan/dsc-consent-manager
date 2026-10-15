package com.seamware.consentmanager.service;

import com.seamware.consentmanager.config.ConsentManagerConfiguration;
import com.seamware.consentmanager.domain.Consent;
import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.domain.User;
import com.seamware.consentmanager.repository.ConsentRepository;
import io.micronaut.core.annotation.NonNull;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Walks the chain of consents a record supersedes, newest first, for {@code includeLineage}.
 *
 * <p>Every hop is read under the same predicate as the entry point - the subject's own {@code
 * user_id}, or the participant as provider or consumer - so the chain stops at the first record the
 * caller may not read rather than disclosing it. The walk also stops at the configured depth and at
 * a record already visited: {@code consents_parent_not_self} forbids a one-row loop but not one
 * across rows, and a cycle must end rather than hang the request.
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
        return walk(consent, parentId -> consents.findByIdAndUserId(parentId, subject.getId()));
    }

    /**
     * The records {@code consent} supersedes that name the participant as a party, newest first.
     */
    @NonNull
    public List<Consent> forParticipant(@NonNull Participant caller, @NonNull Consent consent) {
        return walk(consent, parentId -> consents.findByIdForParticipant(parentId, caller.getId()));
    }

    /**
     * Follows {@code parentConsentId} from {@code start} through the records {@code readable}
     * yields.
     */
    private List<Consent> walk(Consent start, Function<UUID, Optional<Consent>> readable) {
        int maxDepth = settings.getLineageMaxDepth();
        List<Consent> chain = new ArrayList<>();
        Set<UUID> visited = new HashSet<>();
        visited.add(start.getId());
        UUID parentId = start.getParentConsentId();
        while (parentId != null && chain.size() < maxDepth && visited.add(parentId)) {
            Optional<Consent> parent = readable.apply(parentId);
            if (parent.isEmpty()) {
                break;
            }
            chain.add(parent.get());
            parentId = parent.get().getParentConsentId();
        }
        return chain;
    }
}
