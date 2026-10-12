package com.seamware.consentmanager.service;

import com.seamware.consentmanager.domain.PrivacyNotice;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;

/**
 * The outcome of a generation request: the notice to serve and whether this call wrote it.
 *
 * <p>{@code created} is what separates the two success codes - {@code 201} for a notice this call
 * stored, {@code 200} for one that was already there. The two party identifiers travel along
 * because the stored row holds participant keys while the representation names identifiers, and on
 * the race-loser path the row served is not the one this call assembled.
 */
public record GeneratedNotice(
        @NonNull PrivacyNotice notice,
        @NonNull String dataProvider,
        @Nullable String dataConsumer,
        boolean created) {}
