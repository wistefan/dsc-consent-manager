# Implementation Plan: Consent Manager - Reimplementation - Notification Service

## Overview
Send two purely informational emails to data subjects: one when a participant requests their
consent (`ConsentService.request`) and one when a participant terminates it
(`ConsentLifecycleService.terminate`). Delivery is best-effort. It runs after the consent write
has committed, never throws, and carries no token or state-changing link. An SMTP implementation
is bound only when `smtp.host` is set; otherwise a logging implementation sends nothing.

### Decisions fixed by this plan (resolved from the ticket and the analysis comment)
- **Package.** The ticket's paths say `eu.prometheusx.consentmanager`. This repo is
  `com.seamware.consentmanager`, so the new code lives in `com.seamware.consentmanager.notification`.
- **Who is notified for termination.** Only the participant-initiated move,
  `ConsentLifecycleService.terminate`. Two other paths also terminate consents, and neither
  notifies:
  - the collateral terminations a subject's own `resume` performs (ADR-0011), because the
    subject caused them;
  - the bulk terminations of unanswered consents in `ParticipantService.deregister` (ADR-0008),
    because they run inside that method's transaction and the participant is leaving the
    dataspace.
  The reviewer (wistefan) confirmed the deregistration exclusion on PR #18, so it is a decision
  rather than a by-product of the transaction boundary. ADR-0015 (Step 4) records both exclusions.
- **Call site.** The notifier is called directly from the two service methods, after `ConsentWriter`
  has returned and so after commit. It is not called from `ConsentLifecycleService.report` or
  `ConsentStateChangeLog`, because those also see the collateral moves. ADR-0011 asks for an outbox
  for *delivery guarantees*. This ticket explicitly requires best-effort, so a lost email on a
  crash between commit and send is accepted. ADR-0015 records this.
- **Notice title.** Read from the consent's frozen `ConsentSnapshot.title()`, never the live
  notice. It is nullable, so the templates need a neutral fallback (e.g. "a privacy notice").
- **Display name.** `User.firstName` (+ `lastName`) when present, otherwise a generic greeting.
  Never fall back to `identifier`, and never derive an address from it.
- **Client link.** New config `consent-manager.notifications.client-url`
  (`NOTIFICATION_CLIENT_URL`, optional). When it is unset, the consent-requested email has no
  link. The link is the bare configured URL and never includes a consent id, token or query
  parameter.
- **Engine.** `micronaut-views-thymeleaf` + `micronaut-email-template` (`TemplateBody`, `MultipartBody`).
  HTML templates use `th:text` and never `th:utext`, so values are escaped. `.txt` templates must
  render in Thymeleaf `TEXT` mode, so a legal name is not HTML-entity-encoded in the text part.
- **What "SMTP configured" means.** The SMTP beans bind only when `smtp.host` is present **and
  non-blank**. `smtp.host` is deliberately **not** written in `application.yml`. A bare
  `${SMTP_HOST}` fails startup when the variable is unset, and the repo's usual `${SMTP_HOST:}`
  makes the key present with an empty value, which a plain `@Requires(property = "smtp.host")`
  treats as set. The value arrives through Micronaut's environment mapping (`SMTP_HOST` →
  `smtp.host`), and a YAML comment in the `smtp:` block documents the variable. Both sides are
  gated by one `Condition` pair (see Step 1), not by `property`/`missingProperty`, so an exported
  `SMTP_HOST=` (empty) also selects the logging implementation.
- **Call-site failure guard.** Both call sites wrap the notifier call in
  `try`/`catch (RuntimeException)` and log ERROR under the calling class
  (`ConsentService`/`ConsentLifecycleService`) with the consent id. The interface contract says
  implementations never throw, but a committed write must never become a `500` because a future
  implementation breaks that contract.
- **Synchronous send with bounded timeouts.** Send on the request thread after commit. Named
  defaults go on `mail.smtp.connectiontimeout` / `timeout` / `writetimeout`, so an unreachable
  server delays a response by a bounded amount and never fails it. No `@Async` and no executor.

## Steps

### Step 1: Notification configuration, SMTP session and the logging implementation
Add the transport configuration, the provider interface and the implementation that sends
nothing, so the application starts and behaves the same with `SMTP_HOST` unset.

Files:
- `src/main/java/com/seamware/consentmanager/notification/UserNotificationService.java`. This is
  the interface from the ticket:
  `consentRequested(User, Consent, Participant)` and `consentTerminated(User, Consent, Participant)`.
  Its Javadoc states the contract: *never throws; a user without an email is skipped silently*.
- `notification/NotificationConfiguration.java`: `@ConfigurationProperties("smtp")` with `host`,
  `port` (default `587`, a named constant), `user`, `password`, `starttls` (default `true`),
  `fromAddress`, `fromName`, and the explicit opt-out `insecureTrustAllCertificates` (default
  `false`, env `SMTP_INSECURE_TRUST_ALL_CERTIFICATES`). `password` is never logged and never
  rendered by `toString`.
- `notification/SmtpConfiguredCondition.java` and `notification/SmtpNotConfiguredCondition.java`:
  two `io.micronaut.context.condition.Condition`s sharing one package-private predicate, true when
  `smtp.host` resolves to a non-blank string. Every SMTP bean (`SmtpSessionFactory`, the
  `fromAddress` validator, Step 2's `SmtpUserNotificationService`) carries
  `@Requires(condition = SmtpConfiguredCondition.class)`, and the logging implementation carries
  `@Requires(condition = SmtpNotConfiguredCondition.class)`. The two are exact complements, so
  exactly one `UserNotificationService` binds whatever the environment holds.
- `notification/SmtpSessionFactory.java` (`@Factory`, `@Requires(condition = SmtpConfiguredCondition.class)`). It
  provides the `jakarta.mail.Session` / Micronaut Email `SessionProvider` that the javamail sender
  uses, built only from `NotificationConfiguration`:
  - Set `mail.smtp.host`/`port`, plus `auth` and an `Authenticator` when `user` is set.
  - Set `mail.smtp.starttls.enable` and `starttls.required` from `starttls`.
  - **Always** set `mail.smtp.ssl.checkserveridentity=true`. Leave `mail.smtp.ssl.trust` unset.
  - Set the three timeouts from named constants.
  - When `insecureTrustAllCertificates` is true, set `ssl.trust=*` and
    `checkserveridentity=false`, and log a WARN once at startup naming the property.
  - Fail startup when `fromAddress` is blank while SMTP is configured. The message names the
    `SMTP_FROM_ADDRESS` variable. Use a `@Context`/`@PostConstruct` validator in the style of
    `IdentityProviderRegistryValidator`.
  - Verify how the `micronaut-email-javamail` module (version from `micronaut-parent` 5.2.0)
    obtains its session. If it ships its own `SessionProvider`, use `@Replaces`, so there is
    exactly one session source and it is not configured under `javamail.*`.
- `notification/LoggingUserNotificationService.java`: `@Singleton`,
  `@Requires(condition = SmtpNotConfiguredCondition.class)`. It logs at DEBUG which message would have gone to
  which user id and consent id. It logs no email address and sends nothing.
- `config/ConsentManagerConfiguration.java`: add a nested `Notifications` class with `clientUrl`
  (nullable `URI`).
- `src/main/resources/application.yml`: add an `smtp:` block bound to `SMTP_PORT`, `SMTP_USER`,
  `SMTP_PASSWORD`, `SMTP_STARTTLS`, `SMTP_FROM_ADDRESS`, `SMTP_FROM_NAME` and
  `SMTP_INSECURE_TRUST_ALL_CERTIFICATES`. Do **not** add a `host:` key. Instead, a comment at the
  top of the block names `SMTP_HOST`, says it reaches `smtp.host` through Micronaut's environment
  mapping, and says notifications are disabled when it is unset or blank. That comment is what
  satisfies the "document every env var in `application.yml`" convention. Add
  `consent-manager.notifications.client-url: ${NOTIFICATION_CLIENT_URL:}`, or an equivalent
  that leaves it null when unset.
- `.env.sample`: rewrite the "Email / SMTP" block to match the ticket's table. Rename `SMTP_FROM`
  to `SMTP_FROM_ADDRESS`, and add `SMTP_FROM_NAME`, `SMTP_STARTTLS`,
  `SMTP_INSECURE_TRUST_ALL_CERTIFICATES` (marked dev-only, warns at startup) and
  `NOTIFICATION_CLIENT_URL`. Mark `SMTP_HOST` as "notifications disabled when unset" and
  `SMTP_USER`/`SMTP_PASSWORD` as optional.

Tests (`src/test/java/com/seamware/consentmanager/notification/`):
- `NotificationBindingTest`. It runs against the **real** `application.yml`, not a hand-built
  property map, so a future `host: ${SMTP_HOST:}` line in the YAML fails it. Parameterize over the
  `smtp.host` values {absent, `""`, `"   "`} → `LoggingUserNotificationService` is the only
  `UserNotificationService` and no `Session` bean exists, and `localhost` (with a
  `from-address`) → the SMTP implementation is the only one. Start each case with
  `ApplicationContext.builder().environments("test").properties(...)` so `application.yml` and
  `application-test.yml` are both loaded. This covers AC 7 and AC 8.
- `SmtpSessionFactoryTest` (`ApplicationContext.run(Map)` with `smtp.host=localhost`):
  - By default, `checkserveridentity=true`, `ssl.trust` is absent and STARTTLS is required.
  - With the opt-out set, `trust=*` and a WARN naming the property is captured. Use a Logback
    `ListAppender`, as `KeyMaterialLeakIT` does.
  - Startup fails without `from-address`.
  Parameterize where the cases share a shape. This covers AC 6.
- Extend `ConsentManagerConfigurationTest` for `notifications.client-url`.

Acceptance: `./mvnw verify` is green, and the application starts with no `SMTP_*` set.

### Step 2: Email templates and `SmtpUserNotificationService`
Render both messages from local templates and send them as one multipart message over SMTP.

Files:
- `pom.xml`: add `io.micronaut.views:micronaut-views-thymeleaf` and
  `io.micronaut.email:micronaut-email-template` (versions managed by the parent). Add
  `com.icegreen:greenmail-junit5` with test scope and an explicit version property.
- Thymeleaf template-mode wiring: `.html` renders in `HTML` mode and `.txt` in `TEXT` mode, for
  example through a `BeanCreatedEventListener` on the views module's template resolver that sets
  the TEXT/HTML mode patterns, with the view suffix handled accordingly. Keep this in
  `notification/` and verify it with a test. Don't hand-roll escaping.
  **Spike this first, before writing any template.** `micronaut-views-thymeleaf` resolves views
  through one configured suffix (`.html` by default), so `TemplateBody` probably cannot find a
  `.txt` view as-is. Settle the naming with a throwaway render test. Either set an empty suffix
  and pass full names (`email/consent-requested.txt`), or keep `.html` and name the text views
  differently while still matching them to `TEXT` mode by pattern. The template-mode test below
  then pins whichever was chosen.
- `src/main/resources/views/email/consent-requested.html` / `.txt`. Variables: `displayName`,
  `participantLegalName`, `noticeTitle`, `clientUrl`. The link block is omitted when `clientUrl`
  is null.
- `src/main/resources/views/email/consent-terminated.html` / `.txt`. Variables: `displayName`,
  `participantLegalName`, `noticeTitle`, `terminatedAt`. The timestamp is UTC ISO-8601 truncated
  to seconds, from a named `DateTimeFormatter` constant. Its source is defined in Step 3.
- No template carries purposes, data categories, the consent id or anything beyond the notice
  title.
- `notification/SmtpUserNotificationService.java`: `@Singleton`,
  `@Requires(condition = SmtpConfiguredCondition.class)`,
  built on Micronaut Email's `EmailSender`.
  - Skip silently, with at most a DEBUG line, when `user.getEmail()` is blank (AC 3).
  - Build the model and send an `Email` whose body is a `MultipartBody` of two `TemplateBody`
    parts (HTML + text) (AC 9).
  - Use subjects that are named constants containing no interpolated values, so no
    attacker-influenced data reaches a header.
  - Set the sender from `fromAddress`/`fromName`.
  - Wrap render + send in one `try`/`catch (RuntimeException)`. On failure, log ERROR through
    this class's own `LoggerFactory.getLogger(SmtpUserNotificationService.class)` with the
    message kind, the consent id and the exception (AC 5, AC 11). Never rethrow. Log the user
    id, not the address.
- Put the model building (display name fallback, title fallback, client URL, timestamp
  formatting) in one small package-private helper, e.g. `NotificationModel`, so the logging
  implementation can log the same kind/ids without duplicating it.

Tests:
- `EmailTemplateRenderingTest`, parameterized over {requested, terminated} × {html, txt}:
  - Each renders with the expected values.
  - A legal name and a title containing `<script>alert(1)</script><a href="x">` appear escaped
    (`&lt;script&gt;`) in HTML and verbatim in text, and no raw `<script` or `<a href="x"` is
    present in the HTML part (AC 10).
  - Null title and null name fall back.
  - The requested template has no link when `clientUrl` is null.
- `SmtpUserNotificationServiceIT`, against an in-process GreenMail SMTP server on a random
  localhost port, with `smtp.starttls=false` for the test only. Parameterized over
  {requested, terminated} × {addressable, no email} (AC 12):
  - An addressable user receives exactly one `multipart/alternative` message with a `text/html`
    and a `text/plain` part.
  - A user with no email receives nothing and nothing throws.
  - Every received body is scanned for absence of `token`, `verify`, `access_token`, `/consents/`,
    the consent id and any URL other than the configured client URL (AC 4).
- Send failure: point `smtp.port` at a closed localhost port. Both methods return normally, and
  an ERROR whose logger name is `SmtpUserNotificationService` is captured (AC 5, AC 11).

Acceptance: `./mvnw verify` is green, and no test configuration points `smtp.host` anywhere
other than `localhost`/GreenMail (AC 8).

### Step 3: Notify on consent request and participant termination
Call the notifier from the two triggering operations, after their write has committed.

Files:
- `service/ConsentService.java`:
  - Inject `UserNotificationService`.
  - In `request`, keep the `RecordedConsent` returned by `writer.request(...)`, which has already
    committed because `ConsentWriter` is the transactional bean. Call
    `notifications.consentRequested(subject, recorded.consent(), caller)` and then return
    `parties.details(recorded)`.
  - `ConsentService` is not `@Transactional`, so the call is outside the transaction. Keep it
    that way, and say so in the method Javadoc.
- `service/ConsentLifecycleService.java`:
  - Inject `UserNotificationService` (the constructor grows by one) and use the existing
    `UserRepository users`.
  - In `terminate`, after `report(...)`, load the subject by `consent.getUserId()` and call
    `notifications.consentTerminated(subject, details.consent(), caller)`.
  - Pass the consent read back from the move, so its `updatedAt` is the committed termination
    time. Step 2's template formats `consent.getUpdatedAt()`. If `updatedAt` turns out not to
    carry the move time, put the event's `occurredAt` on the model instead, and update the
    interface Javadoc to match.
  - Do **not** call it from `report`, `resume` or the collateral path.
  - Guard both call sites (this method and `ConsentService.request`) with
    `try`/`catch (RuntimeException)`, and log ERROR under the calling class with the consent id.
    Never rethrow.
  - Correct `report`'s Javadoc ("whatever notification grows from it"). Subject notification does
    not grow from `report`; it is called from `request`/`terminate` directly, per ADR-0015.
- `service/ConsentStateChangeLog.java`: no behaviour change. Rewrite the one class-Javadoc
  sentence that names it as the seam TICKET-013's subject notification grows from. TICKET-013 is
  called from `request`/`terminate` directly (ADR-0015), and the seam remains TICKET-020's.
  Without this, the next reader would wire a second sender into the seam.
- No change to `ParticipantService.deregister`.

Tests (`src/test/java/com/seamware/consentmanager/service/`):
- `ConsentNotificationIT`. Use `@MicronautTest(transactional = false)`, `PostgresTestResource`,
  `spec.name`, and a `@Replaces(UserNotificationService.class)` capturing double, following
  `ConsentLifecycleSeamIT.CapturingChangeLog`. Seed with `ConsentFixtures` /
  `PrivacyNoticeFixtures`.
  - Parameterized over {request, terminate} × {user with email, user without email}. The
    double is invoked once with the subject, the consent and the acting participant. The
    implementation-level skip for no email is already covered in Step 2.
  - A `resume` that collaterally terminates a sibling consent triggers no `consentTerminated`.
    A rejected request or termination (403/404/409) triggers nothing.
  - **Failure isolation (AC 5).** Configure the double to throw a `RuntimeException` (the
    contract says implementations never throw, and this proves the caller does not depend on
    it). Assert that the request and the termination are still committed: read back the
    `consents` row and its `consent_events` entry. Also assert that the HTTP call returns the same
    success status that `ConsentRequestIT` / `ConsentLifecycleIT` assert for the unguarded path,
    and that an ERROR is captured under `ConsentService` / `ConsentLifecycleService` (Logback
    `ListAppender`).
- Make sure the existing `ConsentRequestIT`, `ConsentLifecycleIT`, `ConsentLifecycleSeamIT`
  and the surface ITs stay green with the logging implementation bound.

Acceptance: AC 1, 2, 3, 5 and 12 are covered end to end. `./mvnw verify` is green.

### Step 4: Documentation and ADR
Record the decision and update the context files.

Files:
- `docs/adr/0015-subject-notifications-are-best-effort-and-informational.md`. Follow the existing
  ADR shape (Status, Date, Ticket TICKET-013 / Taiga #78, Relates to; Context, Decision,
  Consequences). It covers:
  - email is a notification, not part of authentication;
  - best-effort after commit, with no outbox, and why this does not conflict with ADR-0011's
    outbox note (which concerns guaranteed delivery);
  - the call sites, and why resume-collateral and deregistration terminations do not notify;
  - addressability (never derive an address from `identifier`);
  - TLS on by default and the named opt-out;
  - escaping and the minimal template content;
  - the original bugs 13.3, 13.7, 13.8, 13.9 and 18.6, and why they cannot recur.
- `docs/adr/0011-...md`: add a short note under Consequences saying that TICKET-013's subject
  notification is delivered by ADR-0015 on a best-effort footing and that TICKET-020 still owns
  guaranteed participant notification. Do not rewrite the accepted decision.
- `docs/security.md`: a short "Outbound email" paragraph covering no tokens or links that act,
  TLS verification, and escaping.
- `AGENTS.md`:
  - list the final `notification/` contents and `views/email/` under Project Structure;
  - mention ADR-0015 and correct the ADR range;
  - add GreenMail to the test stack;
  - note the Thymeleaf HTML/TEXT template-mode wiring as a gotcha.
  - note as a gotcha that `smtp.host` is absent from `application.yml` on purpose, and that the
    SMTP/logging split is a `Condition` pair on a non-blank host, never `property`/`missingProperty`;
  - record in the best-effort-notification convention that the call sites guard and log too.
- `.env.sample` / `application.yml`: confirm Step 1's entries are complete and match ADR-0015.

Acceptance: the docs match the code, `./mvnw spotless:apply` makes no changes, and
`./mvnw verify` is green.
