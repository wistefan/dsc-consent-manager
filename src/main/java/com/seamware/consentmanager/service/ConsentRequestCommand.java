package com.seamware.consentmanager.service;

import java.util.UUID;

/**
 * A participant's ask that one of its linked users decide on a privacy notice.
 *
 * <p>It carries no status and no scope: a request opens a {@code PENDING} consent over the notice's
 * full terms, and only the subject's own decision can narrow or answer it. Named {@code ...Command}
 * because the request schema generates {@code api.generated.model.ConsentRequest}, which the
 * controller naming both would otherwise have to qualify.
 */
public record ConsentRequestCommand(UUID privacyNoticeId, String userIdentifier) {}
