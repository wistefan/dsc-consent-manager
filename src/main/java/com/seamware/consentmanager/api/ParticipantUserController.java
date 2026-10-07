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
 * Serves the {@code /participants/me/users} operations: the registration surface a participant uses
 * to put its own users into the directory.
 *
 * <p>The participant is {@link ParticipantPrincipal#requireActive()} and nothing else, so an
 * identifier with no row behind it, or one whose record has been deregistered, is refused here
 * rather than served - a departed organization must not be able to re-acquire the affiliations its
 * deregistration removed. The body deliberately cannot name one, and a {@code
 * participantIdentifier} property sent anyway is ignored rather than refused - the generated model
 * does not forbid unknown properties, so such a request succeeds and still links the user to the
 * caller.
 *
 * <p>{@code UserRegistration} here is the generated API model; the service's record of the same
 * name is written out in full to keep the two apart.
 */
@Controller
public class ParticipantUserController extends AbstractParticipantUsersController {

    /**
     * Placeholder for the counts of a summary still being assembled. The generated constructor
     * demands all five positionally; every one is then set through its named setter.
     */
    private static final int UNCOUNTED = 0;

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

    /**
     * The published tally. Every count goes in through its own named setter, so reordering the
     * properties of {@code BulkRegistrationSummary.yaml} - and with them the generated
     * constructor's five same-typed parameters - cannot silently transpose one count onto another.
     */
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
     * The published outcome of one entry; a null service outcome is the rejection only this path
     * can report. Exhaustive on purpose: the published enum has to keep pace with {@link
     * RegistrationOutcome}, and a new constant must fail compilation rather than surface as a
     * runtime 500.
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
     * Applies the body to the caller's own link and returns the link as re-read.
     *
     * <p>Three cases, which the {@link JsonNullable} wrapper is here to keep apart: a mentioned
     * value is written, an explicit {@code null} clears the stored one, and a property the body
     * never mentions leaves it alone - so a later property added to this schema cannot silently
     * wipe a local identifier its client never named.
     *
     * <p>{@code 404} for a link the caller does not hold, deliberately indistinguishable from an
     * identifier naming nobody; the service decides that.
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

    /**
     * The service outcome as the published enum.
     *
     * <p>Exhaustive by constant rather than by name: {@code fromValue} would throw at runtime on a
     * constant the spec does not carry.
     */
    private static UserRegistrationResult.OutcomeEnum outcomeOf(RegistrationOutcome outcome) {
        return switch (outcome) {
            case CREATED -> UserRegistrationResult.OutcomeEnum.CREATED;
            case LINKED -> UserRegistrationResult.OutcomeEnum.LINKED;
            case ALREADY_LINKED -> UserRegistrationResult.OutcomeEnum.ALREADY_LINKED;
        };
    }
}
