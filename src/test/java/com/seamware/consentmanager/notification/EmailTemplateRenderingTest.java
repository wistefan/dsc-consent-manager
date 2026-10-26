package com.seamware.consentmanager.notification;

import static com.seamware.consentmanager.notification.NotificationTestFixtures.BENIGN_LEGAL_NAME;
import static com.seamware.consentmanager.notification.NotificationTestFixtures.BENIGN_TITLE;
import static com.seamware.consentmanager.notification.NotificationTestFixtures.FIRST_NAME;
import static com.seamware.consentmanager.notification.NotificationTestFixtures.LAST_NAME;
import static com.seamware.consentmanager.notification.NotificationTestFixtures.TERMINATED_AT_RENDERED;
import static org.assertj.core.api.Assertions.assertThat;

import com.seamware.consentmanager.domain.Consent;
import com.seamware.consentmanager.domain.Participant;
import com.seamware.consentmanager.domain.User;
import io.micronaut.context.ApplicationContext;
import io.micronaut.email.BodyType;
import io.micronaut.email.Contact;
import io.micronaut.email.Email;
import io.micronaut.email.MultipartBody;
import io.micronaut.email.template.TemplateBodyDecorator;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.AbstractConfigurableTemplateResolver;
import org.thymeleaf.templateresolver.TemplateResolution;

/**
 * Renders both templates in both parts through the production decorator: HTML escapes, text stays
 * verbatim, fallbacks apply, and no value set by another party leaves an autolinkable URL.
 */
class EmailTemplateRenderingTest {

    static final URI CLIENT_URL = URI.create("https://wallet.example.org/consents");
    static final String MARKUP = "<script>alert(1)</script><a href=\"x\">";
    static final String ESCAPED_SCRIPT = "&lt;script&gt;";

    /** The neutral termination copy; a copy edit must update this deliberately. */
    static final List<String> TERMINATION_PHRASES =
            List.of(
                    "has ended its consent arrangement with you for",
                    "If you had given consent, it no longer applies.",
                    "If a request was still open, you no longer need to answer it.");

    /** Wording that assumes the subject had consented, which a terminated request did not. */
    static final List<String> ASSUMING_PHRASES =
            List.of("you granted", "revoked", "your consent has been", "withdrawn");

    static final List<String> AUTOLINK_SEQUENCES = List.of("://", "mailto:", "www.");
    static final String HOSTILE_URL_NAME = "https://evil.example/verify";
    static final String HOSTILE_TITLE = "HTTP://evil.example and mailto:x@evil.example";
    static final String HOSTILE_FIRST_NAME = "www.evil.example";

    /** Schemes glued to a preceding word character, which a {@code \\b}-anchored match misses. */
    static final String GLUED_URL_NAME = "ACME_https://evil.example/verify";

    static final String GLUED_TITLE = "1https://evil.example and x_mailto:a@evil.example";
    static final String GLUED_FIRST_NAME = "x_www.evil.example";

    private static final Contact FROM = new Contact(NotificationContexts.FROM_ADDRESS);
    private static final String RECIPIENT = "subject@example.org";
    private static final String WHITESPACE = "\\s+";
    private static final String TAG = "<[^>]+>";
    private static final List<String> TEXT_MODE_SYNTAX = List.of("[#", "[/]", "th:", "${");

    private static ApplicationContext context;
    private static TemplateBodyDecorator decorator;

    enum Part {
        HTML(BodyType.HTML),
        TEXT(BodyType.TEXT);

        final BodyType bodyType;

        Part(BodyType bodyType) {
            this.bodyType = bodyType;
        }
    }

    @BeforeAll
    static void startContext() {
        context = NotificationContexts.start(Map.of());
        decorator = context.getBean(TemplateBodyDecorator.class);
    }

    @AfterAll
    static void stopContext() {
        context.close();
    }

    /** Hostile values for the legal name, the notice title and the first name. */
    record HostileValues(String legalName, String title, String firstName) {}

    static Stream<Arguments> kindsPartsAndHostileValues() {
        List<HostileValues> fixtures =
                List.of(
                        new HostileValues(HOSTILE_URL_NAME, HOSTILE_TITLE, HOSTILE_FIRST_NAME),
                        new HostileValues(GLUED_URL_NAME, GLUED_TITLE, GLUED_FIRST_NAME));
        return kindsAndParts()
                .flatMap(
                        args ->
                                fixtures.stream()
                                        .map(
                                                values ->
                                                        Arguments.of(
                                                                args.get()[0],
                                                                args.get()[1],
                                                                values)));
    }

    static Stream<Arguments> kindsAndParts() {
        return Arrays.stream(NotificationKind.values())
                .flatMap(
                        kind -> Arrays.stream(Part.values()).map(part -> Arguments.of(kind, part)));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("kindsAndParts")
    @DisplayName("renders the subject's name, the participant and the notice title")
    void rendersTheExpectedValues(NotificationKind kind, Part part) {
        User user = NotificationTestFixtures.user(RECIPIENT, FIRST_NAME, LAST_NAME);
        Participant participant = NotificationTestFixtures.participant(BENIGN_LEGAL_NAME);
        Consent consent = NotificationTestFixtures.consent(user, participant, BENIGN_TITLE);

        String body = render(model(kind, user, consent, participant, CLIENT_URL), part);

        assertThat(body)
                .contains(FIRST_NAME + " " + LAST_NAME, BENIGN_LEGAL_NAME, BENIGN_TITLE)
                .doesNotContain(consent.getId().toString(), user.getIdentifier());
        if (kind == NotificationKind.CONSENT_REQUESTED) {
            assertThat(body).contains(CLIENT_URL.toString());
        } else {
            assertThat(body).contains(TERMINATED_AT_RENDERED);
        }
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("kindsAndParts")
    @DisplayName("escapes markup in the HTML part and keeps it verbatim in the text part")
    void escapesMarkupOnlyInHtml(NotificationKind kind, Part part) {
        User user = NotificationTestFixtures.user(RECIPIENT, FIRST_NAME, null);
        Participant participant = NotificationTestFixtures.participant(MARKUP);
        Consent consent = NotificationTestFixtures.consent(user, participant, MARKUP);

        String body = render(model(kind, user, consent, participant, null), part);

        if (part == Part.HTML) {
            assertThat(body).contains(ESCAPED_SCRIPT).doesNotContain("<script", "<a href=\"x\"");
        } else {
            assertThat(body)
                    .contains(MARKUP)
                    .doesNotContain(ESCAPED_SCRIPT)
                    .as("TEXT-mode element syntax must be processed, not printed")
                    .doesNotContain(TEXT_MODE_SYNTAX);
        }
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("kindsAndParts")
    @DisplayName("falls back to a generic greeting and title when the name and title are absent")
    void fallsBackWithoutNameOrTitle(NotificationKind kind, Part part) {
        User user = NotificationTestFixtures.user(RECIPIENT, null, null);
        Participant participant = NotificationTestFixtures.participant(BENIGN_LEGAL_NAME);
        Consent consent = NotificationTestFixtures.consent(user, participant, null);

        String body =
                visibleText(
                        normalized(render(model(kind, user, consent, participant, null), part)));

        assertThat(body)
                .contains("Hello " + NotificationModel.FALLBACK_DISPLAY_NAME + ",")
                .contains(NotificationModel.FALLBACK_NOTICE_TITLE)
                .doesNotContain(user.getIdentifier(), "null");
    }

    @ParameterizedTest(name = "{0} {1} {2}")
    @MethodSource("kindsPartsAndHostileValues")
    @DisplayName("leaves no autolinkable URL in a value another party set")
    void neutralisesLinksInInterpolatedValues(
            NotificationKind kind, Part part, HostileValues values) {
        User user = NotificationTestFixtures.user(RECIPIENT, values.firstName(), null);
        Participant participant = NotificationTestFixtures.participant(values.legalName());
        Consent consent = NotificationTestFixtures.consent(user, participant, values.title());

        String body = render(model(kind, user, consent, participant, CLIENT_URL), part);

        if (kind == NotificationKind.CONSENT_REQUESTED) {
            assertThat(body).contains(CLIENT_URL.toString());
        }
        String withoutClientUrl = body.replace(CLIENT_URL.toString(), "").toLowerCase(Locale.ROOT);
        assertThat(withoutClientUrl).doesNotContain(AUTOLINK_SEQUENCES);
        assertThat(body).contains("evil.example");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Part.class)
    @DisplayName("the consent-requested message carries no link without a client URL")
    void omitsTheLinkWithoutAClientUrl(Part part) {
        User user = NotificationTestFixtures.user(RECIPIENT, FIRST_NAME, LAST_NAME);
        Participant participant = NotificationTestFixtures.participant(BENIGN_LEGAL_NAME);
        Consent consent = NotificationTestFixtures.consent(user, participant, BENIGN_TITLE);

        String body =
                render(
                        model(NotificationKind.CONSENT_REQUESTED, user, consent, participant, null),
                        part);

        assertThat(body).doesNotContain("http", "<a ");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Part.class)
    @DisplayName("the consent-terminated message is neutral about whether consent was given")
    void terminationWordingIsNeutral(Part part) {
        User user = NotificationTestFixtures.user(RECIPIENT, FIRST_NAME, LAST_NAME);
        Participant participant = NotificationTestFixtures.participant(BENIGN_LEGAL_NAME);
        Consent consent = NotificationTestFixtures.consent(user, participant, BENIGN_TITLE);

        String body =
                normalized(
                        render(
                                model(
                                        NotificationKind.CONSENT_TERMINATED,
                                        user,
                                        consent,
                                        participant,
                                        null),
                                part));

        assertThat(body).contains(TERMINATION_PHRASES);
        assertThat(body.toLowerCase(Locale.ROOT)).doesNotContain(ASSUMING_PHRASES);
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("kindsAndParts")
    @DisplayName("resolves .html views in HTML mode and .txt views in TEXT mode")
    void resolvesTheTemplateModeFromTheExtension(NotificationKind kind, Part part) {
        TemplateEngine engine = context.getBean(TemplateEngine.class);
        AbstractConfigurableTemplateResolver resolver =
                context.getBean(AbstractConfigurableTemplateResolver.class);
        String view = part == Part.HTML ? kind.htmlView() : kind.textView();

        TemplateResolution resolution =
                resolver.resolveTemplate(engine.getConfiguration(), null, view, null);

        assertThat(resolution.getTemplateMode())
                .isEqualTo(part == Part.HTML ? TemplateMode.HTML : TemplateMode.TEXT);
        assertThat(resolution.getTemplateResource().exists()).isTrue();
    }

    @Test
    @DisplayName("subjects are fixed and carry no interpolated value")
    void subjectsAreConstant() {
        User user = NotificationTestFixtures.user(RECIPIENT, FIRST_NAME, LAST_NAME);
        Participant participant = NotificationTestFixtures.participant(HOSTILE_URL_NAME);
        Consent consent = NotificationTestFixtures.consent(user, participant, HOSTILE_TITLE);
        Arrays.stream(NotificationKind.values())
                .forEach(
                        kind -> {
                            Email email =
                                    SmtpUserNotificationService.email(
                                                    FROM,
                                                    RECIPIENT,
                                                    model(
                                                            kind,
                                                            user,
                                                            consent,
                                                            participant,
                                                            CLIENT_URL))
                                            .build();
                            assertThat(email.getSubject()).isEqualTo(kind.subject());
                        });
    }

    private static NotificationModel model(
            NotificationKind kind,
            User user,
            Consent consent,
            Participant participant,
            URI clientUrl) {
        return kind == NotificationKind.CONSENT_REQUESTED
                ? NotificationModel.requested(user, consent, participant, clientUrl)
                : NotificationModel.terminated(user, consent, participant);
    }

    private static String render(NotificationModel model, Part part) {
        Email.Builder builder = SmtpUserNotificationService.email(FROM, RECIPIENT, model);
        decorator.decorate(builder);
        MultipartBody body = (MultipartBody) builder.getBody().orElseThrow();
        return body.get(part.bodyType).orElseThrow();
    }

    private static String visibleText(String html) {
        return html.replaceAll(TAG, "");
    }

    private static String normalized(String body) {
        return body.replaceAll(WHITESPACE, " ");
    }
}
