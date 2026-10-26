package com.seamware.consentmanager.service;

import com.seamware.consentmanager.domain.PrivacyNotice;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;

/**
 * Outcome of a generation request: the notice to serve, its party identifiers, and whether this
 * call wrote it ({@code 201}) or found it already stored ({@code 200}).
 */
public record GeneratedNotice(
        @NonNull PrivacyNotice notice,
        @NonNull String dataProvider,
        @Nullable String dataConsumer,
        boolean created) {}
