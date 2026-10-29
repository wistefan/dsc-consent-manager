package com.seamware.consentmanager.support;

import com.seamware.consentmanager.domain.ConsentStatus;
import com.seamware.consentmanager.notification.ParticipantNotificationService;
import com.seamware.consentmanager.service.RecordedConsent;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** A database-free participant notification service for unit tests; records each enqueue. */
public class RecordingParticipantNotifications extends ParticipantNotificationService {

    /** One {@link #enqueue} call, as received. */
    public record Enqueued(RecordedConsent recorded, ConsentStatus held, UUID initiator) {}

    public final List<Enqueued> enqueued = new ArrayList<>();

    public RecordingParticipantNotifications() {
        super(null, null);
    }

    @Override
    public void enqueue(RecordedConsent recorded, ConsentStatus held, UUID initiator) {
        enqueued.add(new Enqueued(recorded, held, initiator));
    }
}
