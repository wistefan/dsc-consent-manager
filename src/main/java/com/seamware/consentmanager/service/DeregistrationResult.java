package com.seamware.consentmanager.service;

import io.micronaut.core.annotation.Nullable;
import java.time.Instant;

/**
 * What a deregistration did: when the record was marked, and the tally of what was closed, unlinked
 * and archived on the way there.
 *
 * <p>{@code deregisteredAt} is null when the record was deleted outright rather than retained,
 * which happens only for a participant no consent and no privacy notice ever named.
 */
public record DeregistrationResult(
        @Nullable Instant deregisteredAt,
        int consentsTerminated,
        long consentsRetained,
        int noticesArchived,
        long linksRemoved) {}
