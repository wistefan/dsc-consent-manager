package com.seamware.consentmanager.service;

import java.util.UUID;

/**
 * A participant's ask that one of its linked users decide on a privacy notice. Opens a {@code
 * PENDING} consent over the notice's full terms; only the subject answers it.
 */
public record ConsentRequestCommand(UUID privacyNoticeId, String userIdentifier) {}
