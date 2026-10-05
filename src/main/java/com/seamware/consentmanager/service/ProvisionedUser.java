package com.seamware.consentmanager.service;

import com.seamware.consentmanager.domain.User;

/**
 * The {@code users} row behind an identifier, and whether this call's own insert produced it.
 *
 * <p>{@code created} is true only for the caller whose insert won the unique constraint: a leading
 * read and a lost race both report false, so exactly one of N concurrent first callers sees true.
 */
public record ProvisionedUser(User user, boolean created) {}
