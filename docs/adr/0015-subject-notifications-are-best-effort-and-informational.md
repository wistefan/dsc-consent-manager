# ADR 0015 — Subject notifications are best-effort and informational

[Documentation](../README.md) › [ADR index](README.md) › ADR 0015

- **Status:** Accepted.
- **Date:** 2026-10-16
- **Ticket:** TICKET-013 (Taiga #78)
- **Relates to:** the `…consentmanager.notification` package (`UserNotificationService`,
  `SmtpUserNotificationService`, `LoggingUserNotificationService`, `NotificationModel`,
  `LinkNeutralizer`, `NotificationExecutorFactory`, `NotificationThreadPool`,
  `SmtpSessionFactory`, `SmtpConfigurationValidator`, the `SmtpConfiguredCondition` /
  `SmtpNotConfiguredCondition` pair) and `src/main/resources/views/email/`; the call sites
  `ConsentService.request` and `ConsentLifecycleService.terminate`;
  [ADR-0011](0011-a-lifecycle-move-is-recorded-here-and-notified-elsewhere.md), which deferred
  delivery to this ticket and TICKET-020; [ADR-0007](0007-erasure-pseudonymises-the-user-and-retains-the-audit-trail.md)
  and [ADR-0008](0008-deregistration-retains-the-participant-and-closes-its-open-business.md),
  whose bulk closings do not notify; [ADR-0009](0009-only-the-data-subject-grants-consent.md),
  which is why a request email can only inform.

## Context

A data subject should hear about two things a participant does to them: asking for their consent,
and terminating it. The ticket's analysis of the original service recorded defects in exactly
this area (13.3, 13.7, 13.8, 13.9 and 18.6; see Consequences). This ADR decides what a
notification is so that five classes of failure have nowhere to return: a notification failing
the consent operation, an email carrying a link or token that acts, interpolated text reaching
the message unchecked, an unverified mail transport, and an address guessed from the identifier.

Under ADR-0009 only the subject grants, and a request confers nothing. An email therefore never
has to *do* anything, and anything an email could do would be a second, weaker authentication
path. That fixes the shape of everything below.

## Decision

**Email is a notification, never part of authentication.** No message carries a token, a consent
id, a verification code, a query parameter or a link that changes state. The only link the service
renders is the bare `consent-manager.notifications.client-url` (`NOTIFICATION_CLIENT_URL`), and
only in the consent-requested email. When that is unset the email has no link. Startup refuses a
configured value that is not an absolute `http(s)` URL with a host. The subject acts by signing in
to their client, exactly as they would without the email.

**Delivery is best-effort, after commit, with no outbox.** The notifier is called once the
`ConsentWriter` write has returned, and so once it has committed. A crash between the commit and
the send loses the email, and that is accepted. This does not contradict ADR-0011's outbox note.
That note is about *guaranteed* delivery, where a lost message is a defect. TICKET-013 asks for an
informational message that may be lost, and TICKET-020's guaranteed participant notification
still owns the outbox when it comes.

**Two call sites, called directly, and nowhere else.** `ConsentService.request` sends
consent-requested and `ConsentLifecycleService.terminate` sends consent-terminated. Neither goes
through `ConsentLifecycleService.report` or `ConsentStateChangeLog`, because that seam also sees
moves the subject must not be emailed about. Four other paths move a consent to `TERMINATED` and
deliberately send nothing:

| Path | Why it does not notify |
|---|---|
| `resume`'s collateral terminations (`ConsentWriter.resume` → `closeRedundant`, ADR-0011) | The subject caused them by resuming. |
| `decide`'s cleanup of redundant open requests (`closeRedundant` with `CLEANUP_TRIGGER`) | The subject caused them by answering one of several. |
| Erasure closing unanswered consents (`UserService.erase`, ADR-0007) | The subject asked to be erased and must not be emailed about it. |
| Deregistration closing unanswered consents (`ParticipantService.deregister`, ADR-0008) | The participant is leaving the dataspace. Confirmed on PR #18. |

None of these is an oversight to be wired up later.

**A call site can never be failed by a notification.** Everything a call site does after the
commit sits in one `try`/`catch (RuntimeException)`: the subject lookup in `terminate`, the model
build and the hand-off. A failure is logged at ERROR under the calling class with the consent id
and is never rethrown. If the subject is gone it is logged and skipped, not answered as `404`.
The interface contract says implementations never throw, but the guard does not rely on that.

**Sending is asynchronous on a bounded executor.** The request thread builds the immutable
`NotificationModel` from the entities and skips a subject without an email. It then submits one
task carrying only the model and the address. The task never touches an entity or the database.
Rendering and the SMTP exchange run on the `@Named("notifications")` `NotificationThreadPool`:

- 2 daemon threads named `notification-sender-*`, a queue of 1000 and `AbortPolicy`. A full
  queue is a dropped notification, logged at ERROR with the kind and consent id.
- At shutdown `drain()` stops intake, waits at most **2 s**, then drops what is still queued and
  logs one WARN with the count. It does not drain the queue: against an unreachable server,
  1000 tasks at a 5 s connect timeout on 2 threads would hold shutdown for about 40 minutes. A
  send still blocked in a socket does not hold the JVM, because the threads are daemon.
- JavaMail's timeouts default to infinite. Here they are **5 s** connect
  (`mail.smtp.connectiontimeout`), **10 s** read (`mail.smtp.timeout`) and **10 s** write
  (`mail.smtp.writetimeout`). They bound how long a sender thread is held, not response latency,
  which the hand-off already decouples from the mail server.
- Not `@Async`: its interceptor would carry entity arguments onto another thread, and its default
  executor's queue is unbounded.

**SMTP is configured by a non-blank `smtp.host`, and only by that.** The SMTP beans bind under
`SmtpConfiguredCondition` and `LoggingUserNotificationService` under its exact complement, so one
implementation always binds and an exported `SMTP_HOST=` selects logging. `smtp.host` is absent
from `application.yml` on purpose: `${SMTP_HOST:}` would make the key present but empty, which
`@Requires(property = …)` treats as set. The logging implementation is synchronous, sends nothing
and logs only the kind, the user id and the consent id at DEBUG.

**The terminated wording is neutral.** `terminate` closes a granted consent *and* an unanswered
request, and the interface carries no held status. The copy says the participant "has ended its
consent arrangement with you", that a given consent no longer applies and that an open request
needs no answer. It never says "you granted" or "revoked".

**An address is only ever `User.email`.** A subject without one is skipped. An address is never
derived from `identifier`, which may be a DID or URI and is not an addressable mailbox. The
greeting uses `firstName`/`lastName` when present and a generic fallback otherwise, never the
identifier. The notice title comes from the consent's frozen `ConsentSnapshot`, with a neutral
fallback, never from the live notice.

**TLS is on by default and the opt-out is named.** STARTTLS is enabled and required,
`mail.smtp.ssl.checkserveridentity` is always `true` and `mail.smtp.ssl.trust` is unset.
`SMTP_INSECURE_TRUST_ALL_CERTIFICATES=true` is the only way to trust every certificate.
`SmtpConfigurationValidator` is a `@Context` bean, so it logs a WARN about it while the
application starts, not on a first send that may come hours later or never. `SMTP_STARTTLS=false`
also warns, and startup fails if it is combined with `SMTP_USER`, so a password never crosses the
network in plaintext. Port 465 (implicit TLS, unsupported) warns too.

**Templates escape, say little, and neutralise links.**
- HTML views use `th:text` only, never `th:utext`. The `.txt` views render in Thymeleaf `TEXT`
  mode (`EmailTemplateModes`), so a legal name is not entity-encoded in the plain part.
- Subjects are constants with no interpolated value, so no caller-influenced data reaches a header.
- A message carries the participant's legal name, the notice title, the greeting and, for a
  termination, the UTC time. It never carries purposes, data categories or the consent id.
- Mail clients autolink URL-shaped text even after escaping. The legal name, notice title and the
  user's name are set by parties other than this service, so `LinkNeutralizer` rewrites
  `<scheme>://` to `<scheme>[:]//`, `mailto:` to `mailto[:]` and `www.` to `www[.]`. The
  configured client URL is the only value not neutralised, because it is deployment config.
- A bare domain with no scheme or `www.` (`acme.io GmbH`) is left as is, because breaking it would
  mangle legitimate legal names. Some clients may autolink it. That residual risk is accepted.

## Consequences

- **Defects 13.3, 13.7, 13.8, 13.9 and 18.6 cannot recur.** The ticket's analysis holds the
  per-number detail. The defects fall into the classes below, and each class is now closed
  structurally:
  - *A notification failure failed the consent operation.* The send runs after commit, off the
    request thread, inside guards at both the call site and the task. `ConsentNotificationIT` and
    `SmtpUserNotificationServiceIT` prove a throwing, refused or hanging send leaves the response
    and the committed row unchanged.
  - *Links that acted, or tokens, in the email.* Templates have no consent id or token variable.
    The delivered-mail scan in `SmtpUserNotificationServiceIT` fails on `token`,
    `access_token`, `/consents/`, any URL the service did not configure or a leftover `mailto:` /
    `www.` sequence, over benign and hostile fixtures alike.
  - *Unescaped or link-bearing interpolated text.* `th:text` plus `LinkNeutralizer`, pinned by the
    template tests.
  - *Certificate checks disabled on the transport.* Identity checking is unconditional, and the
    only opt-out is named and logged at startup.
  - *Mail sent to whatever the identifier looked like.* Only `User.email` is used.
- **A lost email is possible and silent to the subject.** A crash after commit, a full queue, the
  shutdown grace and a mail-server failure all lose the message. A full queue and a send failure
  are logged at ERROR with the consent id, never the address. The shutdown drop is one WARN with a
  count, and a crash leaves no trace. The consent record and its trail stay the authority.
- **No delivery receipt, retry or bounce handling.** Anything that needs one belongs to
  TICKET-020's outbox and not to this package.
- **A future termination path decides for itself.** A new path that moves a consent to
  `TERMINATED` does not notify unless it calls the notifier. Hanging a sender on
  `ConsentStateChangeLog` would email the subject about the four excluded paths above.
