package com.seamware.consentmanager.service;

import com.seamware.consentmanager.domain.ConsentStatus;
import io.micronaut.core.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * What a data subject decided about one privacy notice, and how far they narrowed it.
 *
 * <p>The decision is carried as a status rather than a boolean so the one lifecycle table decides
 * which decisions exist; this record enforces nothing about it. An absent election means the
 * notice's own scope is consented to, while an empty one is a malformed narrowing the service
 * refuses - the two are different, so neither is normalised away here.
 */
public record ConsentDecision(
        UUID privacyNoticeId,
        ConsentStatus decision,
        @Nullable List<String> purposes,
        @Nullable List<String> dataCategories) {}
