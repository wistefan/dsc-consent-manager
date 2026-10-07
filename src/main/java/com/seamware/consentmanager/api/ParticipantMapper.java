package com.seamware.consentmanager.api;

import com.seamware.consentmanager.api.generated.model.Participant;
import com.seamware.consentmanager.api.generated.model.ParticipantEndpoints;
import com.seamware.consentmanager.api.generated.model.ParticipantLegalPerson;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Translates between a stored participant and the API's {@code Participant} representation.
 *
 * <p>Shared by every operation that returns a participant, so the attribute set a caller sees does
 * not drift between them. The {@code endpoints} and {@code legal_person} columns are free-form
 * JSONB maps while the representation is typed, and this is the one place that knows which key
 * carries which property. {@code Participant} here is the generated API model; the stored row is
 * named in full to keep the two apart.
 *
 * <p>Reading a column tolerates what writing it never produces - a key this revision does not name,
 * or a value that is not a string - because the column is shared with deployments and revisions
 * that may write more than this schema publishes.
 */
@Singleton
public class ParticipantMapper {

    /** JSONB key of {@code endpoints.consentNotification}. */
    private static final String CONSENT_NOTIFICATION_KEY = "consentNotification";

    /** JSONB key of {@code legalPerson.registrationNumber}. */
    private static final String REGISTRATION_NUMBER_KEY = "registrationNumber";

    /** JSONB key of {@code legalPerson.headquartersAddress}. */
    private static final String HEADQUARTERS_ADDRESS_KEY = "headquartersAddress";

    /** JSONB key of {@code legalPerson.legalAddress}. */
    private static final String LEGAL_ADDRESS_KEY = "legalAddress";

    /** JSONB key of {@code legalPerson.parentOrganization}. */
    private static final String PARENT_ORGANIZATION_KEY = "parentOrganization";

    /** JSONB key of {@code legalPerson.subOrganization}. */
    private static final String SUB_ORGANIZATION_KEY = "subOrganization";

    /**
     * Every {@code legal_person} key this revision publishes, used to detect a column with none.
     */
    private static final List<String> LEGAL_PERSON_KEYS =
            List.of(
                    REGISTRATION_NUMBER_KEY,
                    HEADQUARTERS_ADDRESS_KEY,
                    LEGAL_ADDRESS_KEY,
                    PARENT_ORGANIZATION_KEY,
                    SUB_ORGANIZATION_KEY);

    /**
     * The representation of a stored participant. Carries no credential, because none is stored.
     */
    public Participant toRepresentation(com.seamware.consentmanager.domain.Participant stored) {
        return new Participant(stored.getIdentifier(), stored.getLegalName())
                .selfDescriptionUri(stored.getSelfDescriptionUri())
                .email(stored.getEmail())
                .legalPerson(toLegalPerson(stored.getLegalPerson()))
                .endpoints(toEndpoints(stored.getEndpoints()))
                .createdAt(atUtc(stored.getCreatedAt()))
                .updatedAt(atUtc(stored.getUpdatedAt()))
                .deregisteredAt(atUtc(stored.getDeregisteredAt()));
    }

    /**
     * The {@code endpoints} column a submitted endpoint object becomes.
     *
     * <p>Never {@code null}: the column is {@code NOT NULL}, so a participant that names no
     * endpoint stores an empty object rather than nothing.
     */
    public Map<String, Object> toEndpointsColumn(ParticipantEndpoints endpoints) {
        Map<String, Object> column = new LinkedHashMap<>();
        if (endpoints != null) {
            put(column, CONSENT_NOTIFICATION_KEY, endpoints.getConsentNotification());
        }
        return column;
    }

    /** The {@code legal_person} column a submitted legal-person object becomes, or {@code null}. */
    public Map<String, Object> toLegalPersonColumn(ParticipantLegalPerson legalPerson) {
        if (legalPerson == null) {
            return null;
        }
        Map<String, Object> column = new LinkedHashMap<>();
        put(column, REGISTRATION_NUMBER_KEY, legalPerson.getRegistrationNumber());
        put(column, HEADQUARTERS_ADDRESS_KEY, legalPerson.getHeadquartersAddress());
        put(column, LEGAL_ADDRESS_KEY, legalPerson.getLegalAddress());
        put(column, PARENT_ORGANIZATION_KEY, legalPerson.getParentOrganization());
        put(column, SUB_ORGANIZATION_KEY, legalPerson.getSubOrganization());
        return column.isEmpty() ? null : column;
    }

    /** An endpoints object with nothing set reads back as absent rather than as an empty object. */
    private static ParticipantEndpoints toEndpoints(Map<String, Object> column) {
        String consentNotification = string(column, CONSENT_NOTIFICATION_KEY);
        return consentNotification == null
                ? null
                : new ParticipantEndpoints().consentNotification(consentNotification);
    }

    /**
     * A legal person none of whose properties is publishable reads back as absent, the same as an
     * endpoints object does - an empty object would claim a legal person with no attributes.
     */
    private static ParticipantLegalPerson toLegalPerson(Map<String, Object> column) {
        if (LEGAL_PERSON_KEYS.stream().allMatch(key -> string(column, key) == null)) {
            return null;
        }
        return new ParticipantLegalPerson()
                .registrationNumber(string(column, REGISTRATION_NUMBER_KEY))
                .headquartersAddress(string(column, HEADQUARTERS_ADDRESS_KEY))
                .legalAddress(string(column, LEGAL_ADDRESS_KEY))
                .parentOrganization(string(column, PARENT_ORGANIZATION_KEY))
                .subOrganization(string(column, SUB_ORGANIZATION_KEY));
    }

    /** An absent key and a value of any other type read alike, since neither is publishable. */
    private static String string(Map<String, Object> column, String key) {
        return column != null && column.get(key) instanceof String value ? value : null;
    }

    /** A property nobody supplied stays out of the column rather than being stored as null. */
    private static void put(Map<String, Object> column, String key, String value) {
        if (value != null) {
            column.put(key, value);
        }
    }

    /** Timestamps are stored as instants and published at UTC, never at the server's zone. */
    private static OffsetDateTime atUtc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
