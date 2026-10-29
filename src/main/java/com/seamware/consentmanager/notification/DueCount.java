package com.seamware.consentmanager.notification;

import io.micronaut.core.annotation.Introspected;
import java.util.UUID;

/** How many outbox rows are due for one participant; the queue-depth gauge's reading. */
@Introspected
public record DueCount(UUID participantId, long due) {}
