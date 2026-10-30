package com.seamware.consentmanager.notification;

import com.seamware.consentmanager.config.ConsentManagerConfiguration.ParticipantNotifications;
import com.seamware.consentmanager.domain.Participant;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.model.Page;
import io.micronaut.data.model.Pageable;
import io.micronaut.data.model.Sort;
import jakarta.inject.Singleton;

/**
 * A participant's notification delivery history, newest first, paged as {@code GET /participants}
 * is: an omitted size takes the configured default and a larger one is clamped to the ceiling.
 */
@Singleton
public class NotificationHistoryService {

    private static final int FIRST_PAGE = 0;

    private static final String CREATED_AT_PROPERTY = "createdAt";

    /** Tie-break: rows one transaction enqueues share its {@code now()}; ids are UUIDv7. */
    private static final String ID_PROPERTY = "id";

    private static final Sort NEWEST_FIRST =
            Sort.of(Sort.Order.desc(CREATED_AT_PROPERTY), Sort.Order.desc(ID_PROPERTY));

    private final NotificationOutboxRepository outbox;

    private final ParticipantNotifications settings;

    /** Wires the outbox and the paging bounds. */
    public NotificationHistoryService(
            NotificationOutboxRepository outbox, ParticipantNotifications settings) {
        this.outbox = outbox;
        this.settings = settings;
    }

    /** The page of rows owed to {@code participant}; never another participant's. */
    public Page<NotificationOutboxEntry> history(
            Participant participant, @Nullable Integer page, @Nullable Integer size) {
        Pageable pageable =
                Pageable.from(page == null ? FIRST_PAGE : page, applicableSize(size), NEWEST_FIRST);
        return outbox.findByParticipantId(participant.getId(), pageable);
    }

    private int applicableSize(@Nullable Integer size) {
        return Math.min(
                size == null ? settings.getPageDefaultSize() : size, settings.getPageMaxSize());
    }
}
