package com.seamware.consentmanager.service;

import io.micronaut.core.annotation.Nullable;

/**
 * What an erasure did: the opaque identifier the record now carries, and the tally of what was
 * closed and unlinked on the way there.
 *
 * <p>{@code pseudonym} is null when the record was deleted outright rather than pseudonymised,
 * which is what happens when no consent referred to it and nothing therefore had to be retained.
 * When it is present it is published to the data subject exactly once, in the response to their own
 * erasure, because it is the only handle left onto the consents that were.
 */
public record ErasureResult(
        @Nullable String pseudonym,
        int consentsRevoked,
        int consentsTerminated,
        int linksRemoved) {}
