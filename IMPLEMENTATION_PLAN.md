# Implementation Plan: Consent Manager - Reimplementation - Notification Service

## Overview
Send two purely informational emails to data subjects: one when a participant requests their
consent (`ConsentService.request`) and one when a participant terminates it
(`ConsentLifecycleService.terminate`). Delivery is best-effort and asynchronous. It is handed off
after the consent write has committed, never throws, never delays the response on the mail server,
and carries no token or state-changing link. An SMTP implementation is bound only when
`smtp.host` is set and non-blank; otherwise a logging implementation sends nothing.

### Decisions fixed by this plan (resolved from the ticket and the analysis comment)
- **Package.** The ticket's paths say `eu.prometheusx.consentmanager`. This repo is
  `com.seamware.consentmanager`, so the new code lives in `com.seamware.consentmanager.notification`.
- **Who is notified for termination.** Only the participant-initiated move,
  `ConsentLifecycleService.terminate`. Four other paths also move consents to `TERMINATED`, and
  none of them notifies:
  - the collateral terminations a subject's own `resume` performs (`ConsentWriter.resume` →
    `closeRedundant`, ADR-0011), because the subject caused them;
  - the redundant open requests `ConsentWriter.decide` closes through `closeRedundant` with
    `CLEANUP_TRIGGER` when the subject answers one of several, because the subject caused them;
  - the unanswered consents `UserService.erase` closes (`close(userId, UNANSWERED_STATUSES,
    TERMINATED)`, ADR-0007), because the subject asked to be erased and must not be emailed
    about it;
  - the bulk terminations of unanswered consents in `ParticipantService.deregister` (ADR-0008),
    because the participant is leaving the dataspace.
  The reviewer (wistefan) confirmed the deregistration exclusion on PR #18, so it is a decision
  rather than a by-product of the transaction boundary. ADR-0015 (Step 4) lists all four
  exclusions, so none of them reads later as an oversight to be wired up.
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
- **Call-site failure guard.** Everything a call site does after the write commits sits inside
  one `try`/`catch (RuntimeException)`: in `terminate` that includes the subject lookup, not only
  the notifier call. The guard logs ERROR under the calling class
  (`ConsentService`/`ConsentLifecycleService`) with the consent id and never rethrows. The
  interface contract says implementations never throw, but a committed write must never become a
  `404`/`500` because of anything that runs after commit.
- **Asynchronous send (decided by wistefan on PR #18).** The request thread only builds the
  message model and hands it to a dedicated executor; rendering and the SMTP exchange run there.
  A slow or unreachable mail server therefore never delays a request/terminate response.
  - Everything that needs the request's state stays on the request thread: the subject lookup,
    the blank-email skip and building the immutable `NotificationModel` from the entities. The
    task carries only that model and the recipient address, never a JPA/JDBC entity, and does
    no database access.
  - The executor is `@Named("notifications")`, produced by a `@Factory` gated by
    `SmtpConfiguredCondition`: a `ThreadPoolExecutor` with `NOTIFICATION_SENDER_THREADS = 2`
    threads and a bounded queue of `NOTIFICATION_QUEUE_CAPACITY = 1000`, named threads, and
    `AbortPolicy`. It is shut down with the context (`@Bean(preDestroy = "shutdown")`).
    A full queue is a dropped notification: the `RejectedExecutionException` is caught in
    `SmtpUserNotificationService` and logged at ERROR with the kind and consent id.
  - Not `@Async`: Micronaut's async interceptor would run the whole method, entity arguments
    included, on another thread, and its default executor has an unbounded queue.
  - Messages still queued at shutdown or held by a crashed process are lost. This is within the
    ticket's best-effort contract and ADR-0015 records it.
  - The SMTP timeouts stay, now to bound how long a sender thread is held rather than response
    latency: `mail.smtp.connectiontimeout` 5 s, `mail.smtp.timeout` 10 s,
    `mail.smtp.writetimeout` 10 s, as named `Duration` constants (JavaMail's own default is
    infinite). ADR-0015 records the values.
  - The logging implementation stays synchronous; it only writes a DEBUG line.

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
  `smtp.host` resolves to a non-blank string. Every SMTP bean (`SmtpSessionFactory`,
  `SmtpConfigurationValidator`, Step 2's `SmtpUserNotificationService`) carries
  `@Requires(condition = SmtpConfiguredCondition.class)`, and the logging implementation carries
  `@Requires(condition = SmtpNotConfiguredCondition.class)`. The two are exact complements, so
  exactly one `UserNotificationService` binds whatever the environment holds.
- `notification/SmtpSessionFactory.java` (`@Factory`, `@Requires(condition = SmtpConfiguredCondition.class)`). It
  provides the `jakarta.mail.Session` / Micronaut Email `SessionProvider` that the javamail sender
  uses, built only from `NotificationConfiguration`:
  - Set `mail.smtp.host`/`port`, plus `auth` and an `Authenticator` when `user` is set.
  - Set `mail.smtp.starttls.enable` and `starttls.required` from `starttls`.
  - **Always** set `mail.smtp.ssl.checkserveridentity=true`. Leave `mail.smtp.ssl.trust` unset.
  - Set the three timeouts from named constants (connect 5 s, read 10 s, write 10 s).
  - When `insecureTrustAllCertificates` is true, set `ssl.trust=*` and
    `checkserveridentity=false`. The factory does **not** log the warning: the `Session` is a
    lazy singleton that is first built on the first send, possibly hours after boot or never.
- `notification/SmtpConfigurationValidator.java` (`@Context`,
  `@Requires(condition = SmtpConfiguredCondition.class)`, `@PostConstruct`, in the style of
  `IdentityProviderRegistryValidator`). Because it is `@Context`, it runs eagerly while the
  application context starts, so both of these happen at startup whether or not mail is sent:
  - Fail startup when `fromAddress` is blank while SMTP is configured. The message names the
    `SMTP_FROM_ADDRESS` variable.
  - When `insecureTrustAllCertificates` is true, log one WARN naming
    `SMTP_INSECURE_TRUST_ALL_CERTIFICATES` and saying that server certificates and host names
    are not verified (AC 6).
  - Verify how the `micronaut-email-javamail` module (version from `micronaut-parent` 5.2.0)
    obtains its session. If it ships its own `SessionProvider`, use `@Replaces`, so there is
    exactly one session source and it is not configured under `javamail.*`.
- `notification/NotificationExecutorFactory.java` (`@Factory`,
  `@Requires(condition = SmtpConfiguredCondition.class)`): the `@Named("notifications")`
  `ExecutorService` described under "Asynchronous send", with its sizes as named constants.
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
  `UserNotificationService` and neither a `Session` nor a `notifications` executor bean exists,
  and `localhost` (with a
  `from-address`) → the SMTP implementation is the only one. Start each case with
  `ApplicationContext.builder().environments("test").properties(...)` so `application.yml` and
  `application-test.yml` are both loaded. This covers AC 7 and AC 8.
- `SmtpSessionFactoryTest` (`ApplicationContext.run(Map)` with `smtp.host=localhost`):
  - By default, `checkserveridentity=true`, `ssl.trust` is absent and STARTTLS is required.
  - With the opt-out set, `trust=*` on the `Session`.
  - With the opt-out set, a WARN naming the property is captured **right after
    `ApplicationContext.run(...)` returns and before any `Session` bean is fetched**, which proves
    it is logged at startup and not on first send. Use a Logback `ListAppender`, as
    `KeyMaterialLeakIT` does. Without the opt-out, no such WARN is captured.
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
  `terminate` is permitted from a granted consent **and** from an unanswered request, and the
  ticket's interface carries no held status, so the copy is neutral and true for both. For
  example: "<participant> has ended its consent arrangement with you for <notice>. If you had
  given consent, it no longer applies. If a request was still open, you no longer need to answer
  it." It never says "you granted" or "your consent has been revoked".
- No template carries purposes, data categories, the consent id or anything beyond the notice
  title.
- `notification/SmtpUserNotificationService.java`: `@Singleton`,
  `@Requires(condition = SmtpConfiguredCondition.class)`,
  built on Micronaut Email's `EmailSender` and the `@Named("notifications")` executor.
  - On the calling thread: skip silently, with at most a DEBUG line, when `user.getEmail()` is
    blank (AC 3); otherwise build the `NotificationModel` and submit one task. Catch
    `RejectedExecutionException` around the submit and log it at ERROR as a dropped
    notification. The method then returns without waiting.
  - In the task: send an `Email` whose body is a `MultipartBody` of two `TemplateBody`
    parts (HTML + text) (AC 9).
  - Use subjects that are named constants containing no interpolated values, so no
    attacker-influenced data reaches a header.
  - Set the sender from `fromAddress`/`fromName`.
  - Wrap the task's render + send in one `try`/`catch (RuntimeException)`. On failure, log ERROR through
    this class's own `LoggerFactory.getLogger(SmtpUserNotificationService.class)` with the
    message kind, the consent id and the exception (AC 5, AC 11). Never rethrow. Log the user
    id, not the address.
- Put the model building (display name fallback, title fallback, client URL, timestamp
  formatting) in one small package-private helper, e.g. `NotificationModel`, so the logging
  implementation can log the same kind/ids without duplicating it.
- **Neutralise links in interpolated values.** Escaping stops markup, but mail clients autolink
  any URL-shaped text in both the plain-text and the HTML part. The participant's legal name
  (self-registration, PATCH), the snapshot title and the user's name are all set by parties
  other than this service, so they could carry a clickable phishing link in a mail sent from the
  service's own sender. `NotificationModel` passes each of these values through one
  package-private `LinkNeutralizer` before they reach either template:
  - a URI scheme separator (`<scheme>://`, matched case-insensitively for any scheme) becomes
    `<scheme>[:]//`;
  - `mailto:` becomes `mailto[:]`;
  - a leading `www.` on a word becomes `www[.]`.
  The configured client URL is the only value that is not neutralised, because it comes from
  deployment config. A bare domain without a scheme or `www.` (e.g. `acme.io GmbH`) is left as
  is, because breaking it would mangle legitimate legal names. That residual risk is accepted
  and recorded in ADR-0015. The patterns are named constants.

Tests:
- `EmailTemplateRenderingTest`, parameterized over {requested, terminated} × {html, txt}:
  - Each renders with the expected values.
  - A legal name and a title containing `<script>alert(1)</script><a href="x">` appear escaped
    (`&lt;script&gt;`) in HTML and verbatim in text, and no raw `<script` or `<a href="x"` is
    present in the HTML part (AC 10).
  - Null title and null name fall back.
  - A legal name, a title and a first name containing `https://evil.example/verify`,
    `HTTP://evil.example`, `mailto:x@evil.example` and `www.evil.example` render in both parts
    with no `://`, `mailto:` or `www.` sequence left that a client would autolink. The configured
    client URL still renders intact.
  - The requested template has no link when `clientUrl` is null.
  - The terminated template carries the neutral wording above and none of "you granted",
    "revoked", or any other phrase that assumes the subject had consented. Pin the wording with
    a named expected-phrase constant so a later copy edit has to update the test deliberately.
- `SmtpUserNotificationServiceIT`, against an in-process GreenMail SMTP server on a random
  localhost port, with `smtp.starttls=false` for the test only. Parameterized over
  {requested, terminated} × {addressable, no email} (AC 12):
  - Delivery is asynchronous, so wait with GreenMail's `waitForIncomingEmail(timeout, count)`
    under a named timeout constant, never a fixed sleep.
  - An addressable user receives exactly one `multipart/alternative` message with a `text/html`
    and a `text/plain` part.
  - A user with no email receives nothing and nothing throws. To prove "nothing" without a
    sleep, follow it with an addressable sentinel call and assert that only the sentinel arrives.
  - Every received body is scanned for absence of `token`, `verify`, `access_token`, `/consents/`,
    the consent id and any URL other than the configured client URL (AC 4). The scan runs over
    the benign fixture **and** over a hostile one whose participant legal name is
    `ACME - confirm at https://evil.example/verify` and whose notice title carries a URL, so it
    proves the delivered mail contains no URL the service did not configure itself.
- Send failure: point `smtp.port` at a closed localhost port. Both methods return normally, and
  an ERROR whose logger name is `SmtpUserNotificationService` is captured, polled with
  `support/Await` because it is logged on the sender thread (AC 5, AC 11).
- Does not block: point `smtp.port` at a localhost `ServerSocket` that accepts and never
  answers. Both methods return within a named bound well below the 5 s connect timeout.
- Queue full: a unit test with a saturated single-slot executor shows that a rejected submit
  returns normally and logs a dropped-notification ERROR.

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
    `notifications.consentTerminated(subject, details.consent(), caller)`. The
    `users.findById(...)` call sits **inside** the guard's `try`. An empty result is logged at
    ERROR with the consent id and skipped, never thrown: no `orElseThrow(NotFoundException::new)`
    after commit. The FK restrict makes it unlikely, but the guard depends on nothing after
    commit.
  - Pass the consent read back from the move, so its `updatedAt` is the committed termination
    time. Step 2's template formats `consent.getUpdatedAt()`. If `updatedAt` turns out not to
    carry the move time, put the event's `occurredAt` on the model instead, and update the
    interface Javadoc to match.
  - Do **not** call it from `report`, `resume` or the collateral path.
  - Guard both call sites (this method and `ConsentService.request`) with
    `try`/`catch (RuntimeException)` around everything after the write, and log ERROR under the
    calling class with the consent id. Never rethrow.
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
  - None of the four excluded termination paths triggers `consentTerminated`: a `resume` that
    collaterally terminates a sibling, a `decide` that closes a redundant open request, an
    erasure that closes an unanswered request, and a deregistration that closes one.
    Parameterize over them. A rejected request or termination (403/404/409) triggers nothing.
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
  - the call sites, and why the four other termination paths (resume collateral, `decide`'s
    redundant-request cleanup, erasure, deregistration) do not notify;
  - asynchronous hand-off on the bounded `notifications` executor, what is lost on a full queue
    or at shutdown, and the SMTP timeout values (5 s / 10 s / 10 s) that bound a sender thread;
  - the neutral consent-terminated wording, because `terminate` also closes unanswered requests;
  - addressability (never derive an address from `identifier`);
  - TLS on by default and the named opt-out, whose warning is logged at startup by the eager
    validator rather than on first send;
  - escaping and the minimal template content;
  - neutralising URL-shaped text in participant- and user-supplied values, and the accepted
    residual risk of bare domains;
  - the original bugs 13.3, 13.7, 13.8, 13.9 and 18.6, and why they cannot recur.
- `docs/adr/0011-...md`: add a short note under Consequences saying that TICKET-013's subject
  notification is delivered by ADR-0015 on a best-effort footing and that TICKET-020 still owns
  guaranteed participant notification. Do not rewrite the accepted decision.
- `docs/security.md`: a short "Outbound email" paragraph covering no tokens or links that act,
  TLS verification, escaping, and link neutralisation in interpolated values.
- `AGENTS.md`:
  - list the final `notification/` contents and `views/email/` under Project Structure;
  - mention ADR-0015 and correct the ADR range;
  - add GreenMail to the test stack;
  - note the Thymeleaf HTML/TEXT template-mode wiring as a gotcha.
  - note as a gotcha that `smtp.host` is absent from `application.yml` on purpose, and that the
    SMTP/logging split is a `Condition` pair on a non-blank host, never `property`/`missingProperty`;
  - in the best-effort-notification convention, correct "With `smtp.host` unset" to "With
    `smtp.host` unset **or blank**", so it matches the `Condition` pair and does not point the
    next agent at `missingProperty` (review comment 6043);
  - record in that convention that sending is asynchronous on the `notifications` executor and
    that the call sites guard and log too, the subject lookup included.
- `.env.sample` / `application.yml`: confirm Step 1's entries are complete and match ADR-0015.

Acceptance: the docs match the code, `./mvnw spotless:apply` makes no changes, and
`./mvnw verify` is green.
