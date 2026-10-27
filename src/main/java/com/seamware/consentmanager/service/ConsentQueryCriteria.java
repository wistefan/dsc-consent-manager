package com.seamware.consentmanager.service;

import com.seamware.consentmanager.domain.ConsentStatus;
import io.micronaut.core.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * Status/notice filter and paging for {@code GET /consents/me} and {@code GET /consents}. An absent
 * field narrows nothing; an empty {@code statuses} defaults per listing in the service.
 */
public record ConsentQueryCriteria(
        @Nullable List<ConsentStatus> statuses,
        @Nullable UUID privacyNoticeId,
        @Nullable Integer page,
        @Nullable Integer size) {}
