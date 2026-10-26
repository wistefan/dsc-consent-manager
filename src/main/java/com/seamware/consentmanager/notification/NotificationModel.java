package com.seamware.consentmanager.notification;

import com.seamware.consentmanager.domain.Consent;
import com.seamware.consentmanager.domain.ConsentSnapshot;
import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.domain.User;
import io.micronaut.core.annotation.Nullable;
import java.net.URI;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Immutable message model built on the request thread, so the send task holds no entity. Every
 * value another party set is passed through {@link LinkNeutralizer}; the client URL is not.
 */
record NotificationModel(
        NotificationKind kind,
        UUID userId,
        UUID consentId,
        String displayName,
        String participantLegalName,
        String noticeTitle,
        @Nullable String clientUrl,
        @Nullable String terminatedAt) {

    /** Greeting for a user with neither a first nor a last name. */
    static final String FALLBACK_DISPLAY_NAME = "there";

    static final String FALLBACK_PARTICIPANT_NAME = "A participant";
    static final String FALLBACK_NOTICE_TITLE = "a privacy notice";

    /** UTC ISO-8601, whole seconds. */
    static final DateTimeFormatter TERMINATED_AT_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    static final String DISPLAY_NAME = "displayName";
    static final String PARTICIPANT_LEGAL_NAME = "participantLegalName";
    static final String NOTICE_TITLE = "noticeTitle";
    static final String CLIENT_URL = "clientUrl";
    static final String TERMINATED_AT = "terminatedAt";

    private static final String NAME_SEPARATOR = " ";

    /** Model of the consent-requested message; without a client URL the message has no link. */
    static NotificationModel requested(
            User user, Consent consent, Participant participant, @Nullable URI clientUrl) {
        return new NotificationModel(
                NotificationKind.CONSENT_REQUESTED,
                user.getId(),
                consent.getId(),
                displayName(user),
                participantName(participant),
                noticeTitle(consent),
                clientUrl == null ? null : clientUrl.toString(),
                null);
    }

    /** Model of the consent-terminated message, timed by the consent's {@code updatedAt}. */
    static NotificationModel terminated(User user, Consent consent, Participant participant) {
        return new NotificationModel(
                NotificationKind.CONSENT_TERMINATED,
                user.getId(),
                consent.getId(),
                displayName(user),
                participantName(participant),
                noticeTitle(consent),
                null,
                formatInstant(consent.getUpdatedAt()));
    }

    /** The template variables; an absent optional value is a null entry. */
    Map<String, Object> variables() {
        Map<String, Object> variables = new HashMap<>();
        variables.put(DISPLAY_NAME, displayName);
        variables.put(PARTICIPANT_LEGAL_NAME, participantLegalName);
        variables.put(NOTICE_TITLE, noticeTitle);
        variables.put(CLIENT_URL, clientUrl);
        variables.put(TERMINATED_AT, terminatedAt);
        return variables;
    }

    private static String displayName(User user) {
        String name =
                Stream.of(user.getFirstName(), user.getLastName())
                        .filter(part -> part != null && !part.isBlank())
                        .map(String::strip)
                        .collect(Collectors.joining(NAME_SEPARATOR));
        return name.isEmpty() ? FALLBACK_DISPLAY_NAME : LinkNeutralizer.neutralize(name);
    }

    private static String participantName(Participant participant) {
        return orFallback(participant.getLegalName(), FALLBACK_PARTICIPANT_NAME);
    }

    private static String noticeTitle(Consent consent) {
        ConsentSnapshot snapshot = consent.getSnapshot();
        return orFallback(snapshot == null ? null : snapshot.title(), FALLBACK_NOTICE_TITLE);
    }

    private static String orFallback(@Nullable String value, String fallback) {
        return value == null || value.isBlank()
                ? fallback
                : LinkNeutralizer.neutralize(value.strip());
    }

    @Nullable
    private static String formatInstant(@Nullable Instant instant) {
        return instant == null
                ? null
                : TERMINATED_AT_FORMAT.format(instant.truncatedTo(ChronoUnit.SECONDS));
    }
}
