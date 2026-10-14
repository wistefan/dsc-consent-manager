package com.seamware.consentmanager.service;

import com.seamware.consentmanager.domain.ConsentStatus;
import io.micronaut.core.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * The filters and paging one {@code GET /consents/me} request carries, parsed off the query string.
 *
 * <p>Every field is optional and an absent one narrows nothing: a null or empty {@code statuses}
 * admits every state, a null {@code privacyNoticeId} every notice, and null {@code page}/{@code
 * size} take the service's first page and configured default. {@code statuses} is already the
 * domain enum, so an unparseable value was refused before a criteria was built.
 */
public record ConsentQueryCriteria(
        @Nullable List<ConsentStatus> statuses,
        @Nullable UUID privacyNoticeId,
        @Nullable Integer page,
        @Nullable Integer size) {}
