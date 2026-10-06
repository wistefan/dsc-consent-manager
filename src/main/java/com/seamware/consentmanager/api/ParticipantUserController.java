package com.seamware.consentmanager.api;

import com.seamware.consentmanager.api.generated.AbstractParticipantUsersController;
import com.seamware.consentmanager.api.generated.model.BulkRegistrationEntryResult;
import com.seamware.consentmanager.api.generated.model.BulkRegistrationResult;
import com.seamware.consentmanager.api.generated.model.BulkRegistrationSummary;
import com.seamware.consentmanager.api.generated.model.BulkUserRegistration;
import com.seamware.consentmanager.api.generated.model.BulkUserRegistrationEntry;
import com.seamware.consentmanager.api.generated.model.UserRegistration;
import com.seamware.consentmanager.api.generated.model.UserRegistrationResult;
import com.seamware.consentmanager.security.ParticipantPrincipal;
import com.seamware.consentmanager.service.BulkEntryResult;
import com.seamware.consentmanager.service.CallerScope;
import com.seamware.consentmanager.service.RegistrationOutcome;
import com.seamware.consentmanager.service.RegistrationResult;
import com.seamware.consentmanager.service.UserService;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Controller;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

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

    /**
     * Why {@link RegistrationOutcome#REJECTED} cannot reach the single-registration result: that
     * path answers a refusal with a problem detail, so the outcome is unrepresentable rather than
     * merely unused, and the published enum is narrower by three constants to one.
     */
    private static final String UNREACHABLE_REJECTION =
            "registerForParticipant returned %s, which only the bulk path can produce";

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

    /**
     * Always {@code 207}: a batch mixing users this participant has just acquired with users it
     * registered long ago is a success, and no single status says that.
     *
     * <p>Results are one per entry in request order, so a caller correlates by position; each
     * entry's {@code identifier} is echoed as a cross-check. A rejected entry stops neither the
     * entries after it nor the ones already applied.
     */
    @Override
    public HttpResponse<BulkRegistrationResult> registerParticipantUsersBulk(
            ParticipantPrincipal principal, BulkUserRegistration body) {
        List<BulkEntryResult> results =
                users.registerBulkForParticipant(
                        principal.participant(),
                        body.getUsers().stream()
                                .map(ParticipantUserController::toRegistration)
                                .toList());
        BulkRegistrationResult payload =
                new BulkRegistrationResult(
                        results.stream().map(ParticipantUserController::toEntryResult).toList(),
                        summaryOf(results));
        return HttpResponse.status(HttpStatus.MULTI_STATUS).body(payload);
    }

    /**
     * The published tally. Counts are read by name, never by position, so none can be transposed.
     */
    private static BulkRegistrationSummary summaryOf(List<BulkEntryResult> results) {
        Map<RegistrationOutcome, Long> counted =
                results.stream()
                        .collect(
                                Collectors.groupingBy(
                                        BulkEntryResult::outcome, Collectors.counting()));
        Function<RegistrationOutcome, Integer> count =
                outcome -> counted.getOrDefault(outcome, 0L).intValue();
        return new BulkRegistrationSummary(
                results.size(),
                count.apply(RegistrationOutcome.CREATED),
                count.apply(RegistrationOutcome.LINKED),
                count.apply(RegistrationOutcome.ALREADY_LINKED),
                count.apply(RegistrationOutcome.REJECTED));
    }

    /** One entry's fate as the API publishes it; {@code reason} is set for rejections alone. */
    private static BulkRegistrationEntryResult toEntryResult(BulkEntryResult result) {
        return new BulkRegistrationEntryResult(bulkOutcomeOf(result.outcome()))
                .identifier(result.identifier())
                .reason(result.reason());
    }

    /**
     * Exhaustive on purpose: the published bulk enum has to keep pace with {@link
     * RegistrationOutcome}, and a new constant must fail compilation rather than surface as a
     * runtime 500.
     */
    private static BulkRegistrationEntryResult.OutcomeEnum bulkOutcomeOf(
            RegistrationOutcome outcome) {
        return switch (outcome) {
            case CREATED -> BulkRegistrationEntryResult.OutcomeEnum.CREATED;
            case LINKED -> BulkRegistrationEntryResult.OutcomeEnum.LINKED;
            case ALREADY_LINKED -> BulkRegistrationEntryResult.OutcomeEnum.ALREADY_LINKED;
            case REJECTED -> BulkRegistrationEntryResult.OutcomeEnum.REJECTED;
        };
    }

    /** A batch entry as the service's registration; the entry schema declares no identifier. */
    private static com.seamware.consentmanager.service.UserRegistration toRegistration(
            BulkUserRegistrationEntry entry) {
        return new com.seamware.consentmanager.service.UserRegistration(
                entry.getIdentifier(),
                entry.getLocalIdentifier(),
                entry.getEmail(),
                entry.getFirstName(),
                entry.getLastName());
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

    /**
     * The service outcome as the published enum.
     *
     * <p>Exhaustive by constant rather than by name: {@code fromValue} would throw at runtime on a
     * constant the spec does not carry, and the bulk path is about to add one.
     */
    private static UserRegistrationResult.OutcomeEnum outcomeOf(RegistrationOutcome outcome) {
        return switch (outcome) {
            case CREATED -> UserRegistrationResult.OutcomeEnum.CREATED;
            case LINKED -> UserRegistrationResult.OutcomeEnum.LINKED;
            case ALREADY_LINKED -> UserRegistrationResult.OutcomeEnum.ALREADY_LINKED;
            case REJECTED ->
                    throw new IllegalStateException(UNREACHABLE_REJECTION.formatted(outcome));
        };
    }
}
