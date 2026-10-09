package com.seamware.consentmanager.service;

import com.seamware.consentmanager.domain.PrivacyNotice;
import com.seamware.consentmanager.domain.PrivacyNoticePayload;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;

/**
 * A stored notice as a retrieval serves it: the row, its parties by identifier, and the title and
 * payload resolution arrived at.
 *
 * <p>The title and payload travel beside the row rather than in it, because resolution is
 * read-only: a GET that wrote the dereferenced catalog back would make the stored payload depend on
 * who happened to read it. They are the row's own values whenever nothing was resolved.
 */
public record ResolvedNotice(
        @NonNull PrivacyNotice notice,
        @NonNull String dataProvider,
        @Nullable String dataConsumer,
        @Nullable String title,
        @NonNull PrivacyNoticePayload payload,
        @NonNull NoticeResolution resolution) {}
