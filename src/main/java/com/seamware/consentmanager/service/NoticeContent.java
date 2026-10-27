package com.seamware.consentmanager.service;

import com.seamware.consentmanager.domain.PrivacyNoticePayload;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;

/**
 * What a contract yields for a privacy notice: the payload and the separate title column. The title
 * is null for a contract naming neither a subject nor an identifier.
 */
public record NoticeContent(@Nullable String title, @NonNull PrivacyNoticePayload payload) {}
