package com.seamware.consentmanager.notification;

/** The two messages a subject can receive, with their views and fixed subjects. */
enum NotificationKind {
    CONSENT_REQUESTED("consent-requested", "A participant has requested your consent"),
    CONSENT_TERMINATED(
            "consent-terminated", "A participant has ended a consent arrangement with you");

    /** Views folder the email templates live in, below {@code views/}. */
    static final String VIEW_FOLDER = "email/";

    static final String HTML_EXTENSION = ".html";
    static final String TEXT_EXTENSION = ".txt";

    private final String label;
    private final String subject;

    NotificationKind(String label, String subject) {
        this.label = label;
        this.subject = subject;
    }

    /** Stable name used in log lines and view names. */
    String label() {
        return label;
    }

    /** Constant subject line; never carries interpolated values, so nothing reaches a header. */
    String subject() {
        return subject;
    }

    String htmlView() {
        return VIEW_FOLDER + label + HTML_EXTENSION;
    }

    String textView() {
        return VIEW_FOLDER + label + TEXT_EXTENSION;
    }

    @Override
    public String toString() {
        return label;
    }
}
