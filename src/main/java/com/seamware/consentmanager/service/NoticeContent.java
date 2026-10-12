package com.seamware.consentmanager.service;

import com.seamware.consentmanager.domain.PrivacyNoticePayload;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;

/**
 * What a contract yields for a privacy notice: the payload and the title column beside it.
 *
 * <p>Two values rather than one because the title is a column of {@code privacy_notices} and not
 * part of the stored payload. The title is null for a contract naming neither a subject nor an
 * identifier to fall back to.
 */
public record NoticeContent(@Nullable String title, @NonNull PrivacyNoticePayload payload) {}
