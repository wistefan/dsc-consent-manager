package com.seamware.consentmanager.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.error.ForbiddenException;
import com.seamware.consentmanager.security.CatalogPrincipal;
import com.seamware.consentmanager.security.ConsentManagerPrincipal;
import com.seamware.consentmanager.security.ParticipantPrincipal;
import com.seamware.consentmanager.security.Role;
import com.seamware.consentmanager.security.UserPrincipal;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/** Pins that a {@link CallerScope} never reads dataspace-wide for anyone but the catalog. */
@DisplayName("CallerScope")
class CallerScopeTest {

    private static final String ISSUER = "https://idp.example/realms/test";

    private static final String SUBJECT = "subject-under-test";

    private static final String PARTICIPANT_IDENTIFIER = "did:example:participant";

    private static final Participant REGISTERED =
            new Participant(PARTICIPANT_IDENTIFIER, "Participant Ltd", null, null, Map.of(), null);

    @Test
    @DisplayName("refuses a PARTICIPANT scope with no participant row")
    void refusesAParticipantScopeWithoutItsRow() {
        assertThatThrownBy(() -> new CallerScope(Role.PARTICIPANT, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Role.class, names = "PARTICIPANT", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("admits a missing participant for every other role")
    void admitsAMissingParticipantForOtherRoles(Role role) {
        assertThat(new CallerScope(role, null).participant()).isNull();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("grantedScopes")
    @DisplayName("grants each admitted principal its own scope")
    void grantsTheScopeThePrincipalHolds(
            String description, ConsentManagerPrincipal principal, CallerScope expected) {
        assertThat(CallerScope.of(principal)).isEqualTo(expected);
    }

    static Stream<Arguments> grantedScopes() {
        return Stream.of(
                Arguments.of(
                        "a registered participant, bounded by its own row",
                        new ParticipantPrincipal(
                                ISSUER, SUBJECT, PARTICIPANT_IDENTIFIER, REGISTERED),
                        new CallerScope(Role.PARTICIPANT, REGISTERED)),
                Arguments.of(
                        "the catalog, dataspace-wide",
                        new CatalogPrincipal(ISSUER, SUBJECT),
                        new CallerScope(Role.CATALOG, null)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusedPrincipals")
    @DisplayName("fails closed for a principal it must not widen")
    void failsClosed(String description, ConsentManagerPrincipal principal) {
        assertThatThrownBy(() -> CallerScope.of(principal)).isInstanceOf(ForbiddenException.class);
    }

    static Stream<Arguments> refusedPrincipals() {
        return Stream.of(
                Arguments.of(
                        "an unregistered participant",
                        new ParticipantPrincipal(ISSUER, SUBJECT, PARTICIPANT_IDENTIFIER, null)),
                Arguments.of(
                        "a user",
                        new UserPrincipal(
                                ISSUER, SUBJECT, SUBJECT, null, false, null, null, null, null,
                                false)));
    }
}
