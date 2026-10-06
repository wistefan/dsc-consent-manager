package com.seamware.consentmanager.api;

import com.seamware.consentmanager.api.generated.AbstractParticipantUsersController;
import com.seamware.consentmanager.api.generated.model.UserRegistration;
import com.seamware.consentmanager.api.generated.model.UserRegistrationResult;
import com.seamware.consentmanager.security.ParticipantPrincipal;
import com.seamware.consentmanager.service.CallerScope;
import com.seamware.consentmanager.service.RegistrationOutcome;
import com.seamware.consentmanager.service.RegistrationResult;
import com.seamware.consentmanager.service.UserService;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;

/**
 * Serves the {@code /participants/me/users} operations: the registration surface a participant uses
 * to put its own users into the directory.
 *
 * <p>The participant is {@link ParticipantPrincipal#participant()} and nothing else. The body
 * deliberately cannot name one, and a {@code participantIdentifier} property sent anyway is ignored
 * rather than refused - the generated model does not forbid unknown properties, so such a request
 * succeeds and still links the user to the caller.
 *
 * <p>{@code UserRegistration} here is the generated API model; the service's record of the same
 * name is written out in full to keep the two apart.
 */
@Controller
public class ParticipantUserController extends AbstractParticipantUsersController {

    private final UserService users;

    private final UserMapper mapper;

    public ParticipantUserController(UserService users, UserMapper mapper) {
        this.users = users;
        this.mapper = mapper;
    }

    /**
     * {@code 201} when this request created the user, {@code 200} when it only linked or re-linked
     * one that already existed.
     *
     * <p>The returned user's {@code participants} array is scoped to the caller, as on every other
     * route: registering a user does not disclose which other participants they are affiliated
     * with.
     */
    @Override
    public HttpResponse<UserRegistrationResult> registerParticipantUser(
            ParticipantPrincipal principal, UserRegistration body) {
        RegistrationResult result =
                users.registerForParticipant(principal.participant(), toRegistration(body));
        UserRegistrationResult payload =
                new UserRegistrationResult(
                        mapper.toRepresentation(result.user(), CallerScope.of(principal)),
                        outcomeOf(result.outcome()));
        return result.outcome() == RegistrationOutcome.CREATED
                ? HttpResponse.created(payload)
                : HttpResponse.ok(payload);
    }

    /** The body as the service's registration; the participant is supplied separately. */
    private static com.seamware.consentmanager.service.UserRegistration toRegistration(
            UserRegistration body) {
        return new com.seamware.consentmanager.service.UserRegistration(
                body.getIdentifier(),
                body.getLocalIdentifier(),
                body.getEmail(),
                body.getFirstName(),
                body.getLastName());
    }

    /** The service outcome as the published enum, which shares its constant names by contract. */
    private static UserRegistrationResult.OutcomeEnum outcomeOf(RegistrationOutcome outcome) {
        return UserRegistrationResult.OutcomeEnum.fromValue(outcome.name());
    }
}
