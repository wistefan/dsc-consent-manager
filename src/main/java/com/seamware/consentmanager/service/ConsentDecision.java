package com.seamware.consentmanager.service;

import com.seamware.consentmanager.domain.ConsentStatus;
import io.micronaut.core.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * What a data subject decided about one privacy notice, and how far they narrowed it. Unvalidated:
 * {@link ConsentService#decide} refuses a non-decision status. An absent election takes the
 * notice's scope; an empty one is a malformed narrowing, so neither is normalised here.
 */
public record ConsentDecision(
        UUID privacyNoticeId,
        ConsentStatus decision,
        @Nullable List<String> purposes,
        @Nullable List<String> dataCategories) {}
