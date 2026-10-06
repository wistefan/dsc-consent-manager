package com.seamware.consentmanager.service;

/**
 * What an erasure did: the opaque identifier the record now carries, and the tally of what was
 * revoked and unlinked on the way there.
 *
 * <p>The pseudonym is published to the data subject exactly once, in the response to their own
 * erasure, because it is the only handle left onto the consent records that are retained.
 */
public record ErasureResult(String pseudonym, int consentsRevoked, int linksRemoved) {}
