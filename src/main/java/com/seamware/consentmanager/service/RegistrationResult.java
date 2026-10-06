package com.seamware.consentmanager.service;

import com.seamware.consentmanager.domain.User;

/** The user a registration resolved to, and what the call had to do to reach it. */
public record RegistrationResult(User user, RegistrationOutcome outcome) {}
