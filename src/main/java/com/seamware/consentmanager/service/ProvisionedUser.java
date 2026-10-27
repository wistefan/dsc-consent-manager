package com.seamware.consentmanager.service;

import com.seamware.consentmanager.domain.User;

/**
 * The {@code users} row behind an identifier, and whether this call's own insert produced it.
 * Exactly one of N concurrent first callers sees {@code created}: the one that won the constraint.
 */
public record ProvisionedUser(User user, boolean created) {}
