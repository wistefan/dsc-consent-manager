package com.seamware.consentmanager.api;

import com.seamware.consentmanager.api.generated.AbstractParticipantNotificationsController;
import com.seamware.consentmanager.api.generated.model.ParticipantNotificationPage;
import com.seamware.consentmanager.notification.NotificationHistoryService;
import com.seamware.consentmanager.security.ParticipantPrincipal;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;

/**
 * Serves {@code /participants/me/notifications}. Uses {@link
 * ParticipantPrincipal#requireRegistered()}, so a deregistered participant still reads its history.
 */
@Controller
public class ParticipantNotificationController extends AbstractParticipantNotificationsController {

    private final NotificationHistoryService history;

    private final ParticipantNotificationMapper mapper;

    /** Wires the history read and its representation. */
    public ParticipantNotificationController(
            NotificationHistoryService history, ParticipantNotificationMapper mapper) {
        this.history = history;
        this.mapper = mapper;
    }

    /** The caller's own delivery history, newest first. */
    @Override
    public HttpResponse<ParticipantNotificationPage> listCurrentParticipantNotifications(
            ParticipantPrincipal principal, Integer page, Integer size) {
        return HttpResponse.ok(
                mapper.toPage(history.history(principal.requireRegistered(), page, size)));
    }
}
