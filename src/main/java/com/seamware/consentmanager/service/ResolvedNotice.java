package com.seamware.consentmanager.service;

import com.seamware.consentmanager.domain.PrivacyNotice;
import com.seamware.consentmanager.domain.PrivacyNoticePayload;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;

/**
 * A stored notice as a retrieval serves it: the row, its parties, and the resolved title/payload.
 * Resolution is read-only, so title and payload travel beside the row and never write back.
 */
public record ResolvedNotice(
        @NonNull PrivacyNotice notice,
        @NonNull String dataProvider,
        @Nullable String dataConsumer,
        @Nullable String title,
        @NonNull PrivacyNoticePayload payload,
        @NonNull NoticeResolution resolution) {}
