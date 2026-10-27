package com.seamware.consentmanager.notification;

import com.seamware.consentmanager.config.ConsentManagerConfiguration;
import com.seamware.consentmanager.domain.Consent;
import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.domain.User;
import com.seamware.consentmanager.observability.FailureSummary;
import io.micronaut.context.annotation.Requires;
import io.micronaut.email.BodyType;
import io.micronaut.email.Contact;
import io.micronaut.email.Email;
import io.micronaut.email.EmailSender;
import io.micronaut.email.MultipartBody;
import io.micronaut.email.template.TemplateBody;
import io.micronaut.views.ModelAndView;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sends notifications as multipart HTML + text mail on the notification executor. Every failure, a
 * full queue included, is logged rather than thrown.
 */
@Singleton
@Requires(condition = SmtpConfiguredCondition.class)
public class SmtpUserNotificationService implements UserNotificationService {

    private static final Logger LOG = LoggerFactory.getLogger(SmtpUserNotificationService.class);

    private static final String QUEUE_FAILED = "Could not queue {} notification for consent {}: {}";

    private static final String SEND_FAILED =
            "Failed to send {} notification to user {} for consent {}: {}";

    private final EmailSender<?, ?> emailSender;
    private final Executor executor;
    private final Contact sender;
    private final ConsentManagerConfiguration.Notifications notifications;

    SmtpUserNotificationService(
            EmailSender<?, ?> emailSender,
            @Named(NotificationExecutorFactory.EXECUTOR_NAME) Executor executor,
            NotificationConfiguration smtp,
            ConsentManagerConfiguration.Notifications notifications) {
        this.emailSender = emailSender;
        this.executor = executor;
        this.sender = new Contact(smtp.getFromAddress(), blankToNull(smtp.getFromName()));
        this.notifications = notifications;
    }

    @Override
    public void consentRequested(User user, Consent consent, Participant participant) {
        dispatch(
                NotificationKind.CONSENT_REQUESTED,
                user,
                consent,
                () ->
                        NotificationModel.requested(
                                user, consent, participant, notifications.getClientUrl()));
    }

    @Override
    public void consentTerminated(User user, Consent consent, Participant participant) {
        dispatch(
                NotificationKind.CONSENT_TERMINATED,
                user,
                consent,
                () -> NotificationModel.terminated(user, consent, participant));
    }

    /** Builds the message for {@code model}; the bodies are rendered later by the sender. */
    static Email.Builder email(Contact from, String recipient, NotificationModel model) {
        Map<String, Object> variables = model.variables();
        NotificationKind kind = model.kind();
        return Email.builder()
                .from(from)
                .to(recipient)
                .subject(kind.subject())
                .body(
                        new MultipartBody(
                                new TemplateBody<>(
                                        BodyType.HTML,
                                        new ModelAndView<>(kind.htmlView(), variables)),
                                new TemplateBody<>(
                                        BodyType.TEXT,
                                        new ModelAndView<>(kind.textView(), variables))));
    }

    private void dispatch(
            NotificationKind kind, User user, Consent consent, Supplier<NotificationModel> model) {
        try {
            String recipient = user.getEmail();
            if (recipient == null || recipient.isBlank()) {
                LOG.debug(
                        "User {} has no email; {} notification for consent {} skipped",
                        user.getId(),
                        kind,
                        consent.getId());
                return;
            }
            NotificationModel message = model.get();
            executor.execute(() -> send(recipient, message));
        } catch (RejectedExecutionException e) {
            LOG.error(
                    "Notification queue full or stopped; {} notification for consent {} dropped: {}",
                    kind,
                    consent.getId(),
                    FailureSummary.of(e));
        } catch (RuntimeException e) {
            // Building the model reads the user's name, which the failure may quote.
            LOG.error(QUEUE_FAILED, kind, consent.getId(), FailureSummary.of(e));
            LOG.debug(QUEUE_FAILED, kind, consent.getId(), e.getMessage(), e);
        }
    }

    private void send(String recipient, NotificationModel model) {
        try {
            emailSender.send(email(sender, recipient, model));
        } catch (RuntimeException e) {
            // A transport failure may quote the recipient, so its message stays at DEBUG.
            LOG.error(
                    SEND_FAILED,
                    model.kind(),
                    model.userId(),
                    model.consentId(),
                    FailureSummary.of(e));
            LOG.debug(
                    SEND_FAILED,
                    model.kind(),
                    model.userId(),
                    model.consentId(),
                    e.getMessage(),
                    e);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
