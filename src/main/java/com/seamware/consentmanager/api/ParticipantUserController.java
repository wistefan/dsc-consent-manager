package com.seamware.consentmanager.api;

import com.seamware.consentmanager.api.generated.AbstractParticipantUsersController;
import com.seamware.consentmanager.api.generated.model.BulkRegistrationEntryResult;
import com.seamware.consentmanager.api.generated.model.BulkRegistrationResult;
import com.seamware.consentmanager.api.generated.model.BulkRegistrationSummary;
import com.seamware.consentmanager.api.generated.model.BulkUserRegistration;
import com.seamware.consentmanager.api.generated.model.BulkUserRegistrationEntry;
import com.seamware.consentmanager.api.generated.model.ParticipantUserLink;
import com.seamware.consentmanager.api.generated.model.ParticipantUserLinkUpdate;
import com.seamware.consentmanager.api.generated.model.UserRegistration;
import com.seamware.consentmanager.api.generated.model.UserRegistrationResult;
import com.seamware.consentmanager.security.ParticipantPrincipal;
import com.seamware.consentmanager.service.BulkEntryResult;
import com.seamware.consentmanager.service.CallerScope;
import com.seamware.consentmanager.service.RegistrationOutcome;
import com.seamware.consentmanager.service.RegistrationResult;
import com.seamware.consentmanager.service.UserService;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Controller;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.openapitools.jackson.nullable.JsonNullable;

/**
 * Serves {@code /participants/me/users}. The participant is only {@link
 * ParticipantPrincipal#requireActive()}, so a departed one cannot re-acquire its affiliations.
 *
 * <p>A {@code participantIdentifier} in the body is ignored; the user is still linked to the
 * caller.
 */
@Controller
public class ParticipantUserController extends AbstractParticipantUsersController {

    /** Placeholder for the generated constructor's positional counts, each then set by name. */
    private static final int UNCOUNTED = 0;

    private final UserService users;

    private final UserMapper mapper;

    public ParticipantUserController(UserService users, UserMapper mapper) {
        this.users = users;
        this.mapper = mapper;
    }

    /**
     * {@code 201} when this request created the user, {@code 200} when it only (re-)linked one; the
     * returned {@code participants} array is scoped to the caller.
     */
    @Override
    public HttpResponse<UserRegistrationResult> registerParticipantUser(
            ParticipantPrincipal principal, UserRegistration body) {
        RegistrationResult result =
                users.registerForParticipant(principal.requireActive(), toRegistration(body));
        UserRegistrationResult payload =
                new UserRegistrationResult(
                        mapper.toRepresentation(result.user(), CallerScope.of(principal)),
                        outcomeOf(result.outcome()));
        return result.outcome() == RegistrationOutcome.CREATED
                ? HttpResponse.created(payload)
                : HttpResponse.ok(payload);
    }

    /**
     * Always {@code 207}, with one result per entry in request order; a rejected entry stops
     * neither the entries after it nor those already applied.
     */
    @Override
    public HttpResponse<BulkRegistrationResult> registerParticipantUsersBulk(
            ParticipantPrincipal principal, BulkUserRegistration body) {
        List<BulkEntryResult> results =
                users.registerBulkForParticipant(
                        principal.requireActive(),
                        body.getUsers().stream()
                                .map(ParticipantUserController::toRegistration)
                                .toList());
        BulkRegistrationResult payload =
                new BulkRegistrationResult(
                        results.stream().map(ParticipantUserController::toEntryResult).toList(),
                        summaryOf(results));
        return HttpResponse.status(HttpStatus.MULTI_STATUS).body(payload);
    }

    /** The tally, set by named setters so a schema reorder cannot transpose two counts. */
    private static BulkRegistrationSummary summaryOf(List<BulkEntryResult> results) {
        Map<RegistrationOutcome, Long> counted =
                results.stream()
                        .map(BulkEntryResult::outcome)
                        .filter(Objects::nonNull)
                        .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        Function<RegistrationOutcome, Integer> count =
                outcome -> counted.getOrDefault(outcome, 0L).intValue();
        int applied = counted.values().stream().mapToInt(Long::intValue).sum();
        return new BulkRegistrationSummary(UNCOUNTED, UNCOUNTED, UNCOUNTED, UNCOUNTED, UNCOUNTED)
                .total(results.size())
                .created(count.apply(RegistrationOutcome.CREATED))
                .linked(count.apply(RegistrationOutcome.LINKED))
                .alreadyLinked(count.apply(RegistrationOutcome.ALREADY_LINKED))
                .rejected(results.size() - applied);
    }

    /** One entry's fate as the API publishes it; {@code reason} is set for rejections alone. */
    private static BulkRegistrationEntryResult toEntryResult(BulkEntryResult result) {
        return new BulkRegistrationEntryResult(bulkOutcomeOf(result.outcome()))
                .identifier(result.identifier())
                .reason(result.reason());
    }

    /**
     * One entry's published outcome, null meaning rejected; exhaustive so a new constant fails
     * compilation rather than a request.
     */
    private static BulkRegistrationEntryResult.OutcomeEnum bulkOutcomeOf(
            @Nullable RegistrationOutcome outcome) {
        if (outcome == null) {
            return BulkRegistrationEntryResult.OutcomeEnum.REJECTED;
        }
        return switch (outcome) {
            case CREATED -> BulkRegistrationEntryResult.OutcomeEnum.CREATED;
            case LINKED -> BulkRegistrationEntryResult.OutcomeEnum.LINKED;
            case ALREADY_LINKED -> BulkRegistrationEntryResult.OutcomeEnum.ALREADY_LINKED;
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

    /**
     * Patches the caller's own link: a value is written, an explicit {@code null} clears, an
     * omitted property is untouched. A link the caller lacks is a {@code 404}.
     */
    @Override
    public HttpResponse<ParticipantUserLink> updateParticipantUserLink(
            ParticipantPrincipal principal,
            String identifier,
            ParticipantUserLinkUpdate participantUserLinkUpdate) {
        JsonNullable<String> update = participantUserLinkUpdate.getLocalIdentifier_JsonNullable();
        var link =
                update.isPresent()
                        ? users.updateLink(principal.requireActive(), identifier, update.get())
                        : users.linkFor(principal.requireActive(), identifier);
        return HttpResponse.ok(mapper.toLinkRepresentation(identifier, link));
    }

    /** Ends the caller's affiliation with a user; {@code 409} while a granted consent needs it. */
    @Override
    public HttpResponse<Void> unlinkParticipantUser(
            ParticipantPrincipal principal, String identifier) {
        users.unlink(principal.requireActive(), identifier);
        return HttpResponse.noContent();
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

    /** The service outcome as the published enum; by constant, as {@code fromValue} may throw. */
    private static UserRegistrationResult.OutcomeEnum outcomeOf(RegistrationOutcome outcome) {
        return switch (outcome) {
            case CREATED -> UserRegistrationResult.OutcomeEnum.CREATED;
            case LINKED -> UserRegistrationResult.OutcomeEnum.LINKED;
            case ALREADY_LINKED -> UserRegistrationResult.OutcomeEnum.ALREADY_LINKED;
        };
    }
}
