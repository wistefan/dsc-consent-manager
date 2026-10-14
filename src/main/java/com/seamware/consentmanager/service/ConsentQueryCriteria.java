package com.seamware.consentmanager.service;

import com.seamware.consentmanager.domain.ConsentStatus;
import io.micronaut.core.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * The status/notice filter and paging a consent listing request carries, parsed off the query
 * string; shared by {@code GET /consents/me} and {@code GET /consents}.
 *
 * <p>Every field is optional and an absent one narrows nothing: a null {@code privacyNoticeId}
 * admits every notice, and null {@code page}/{@code size} take the service's first page and
 * configured default. A null or empty {@code statuses} admits every state for the subject listing,
 * but defaults to {@code GRANTED} for the participant listing - the default is the service's, not
 * the criteria's. {@code statuses} is already the domain enum, so an unparseable value was refused
 * before a criteria was built.
 */
public record ConsentQueryCriteria(
        @Nullable List<ConsentStatus> statuses,
        @Nullable UUID privacyNoticeId,
        @Nullable Integer page,
        @Nullable Integer size) {}
