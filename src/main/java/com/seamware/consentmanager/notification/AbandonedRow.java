package com.seamware.consentmanager.notification;

import io.micronaut.core.annotation.Introspected;
import java.util.UUID;

/** An outbox row an unfenced sweep abandoned, named for its WARN line. */
@Introspected
public record AbandonedRow(UUID participantId, UUID consentId) {}
