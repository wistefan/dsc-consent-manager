package com.seamware.consentmanager.notification;

import java.util.regex.Pattern;

/**
 * Breaks the URL shapes mail clients autolink, so a value set by another party cannot become a
 * clickable link in a mail from this service's sender. A bare domain is deliberately left intact.
 */
final class LinkNeutralizer {

    /**
     * {@code <scheme>://} for any scheme, case-insensitively. Unanchored on purpose: a {@code \b}
     * would let a scheme glued to a digit or underscore ({@code ACME_https://}) through.
     */
    static final Pattern SCHEME_SEPARATOR =
            Pattern.compile("([a-z][a-z0-9+.-]*)://", Pattern.CASE_INSENSITIVE);

    static final String SCHEME_SEPARATOR_REPLACEMENT = "$1[:]//";

    static final Pattern MAILTO = Pattern.compile("(mailto):", Pattern.CASE_INSENSITIVE);
    static final String MAILTO_REPLACEMENT = "$1[:]";

    /** {@code www.} anywhere, so a glued prefix ({@code x_www.}) is broken too. */
    static final Pattern WWW_PREFIX = Pattern.compile("(www)\\.", Pattern.CASE_INSENSITIVE);

    static final String WWW_PREFIX_REPLACEMENT = "$1[.]";

    private LinkNeutralizer() {}

    /** Returns {@code value} with every autolinkable sequence broken; {@code null} stays null. */
    static String neutralize(String value) {
        if (value == null) {
            return null;
        }
        String result = SCHEME_SEPARATOR.matcher(value).replaceAll(SCHEME_SEPARATOR_REPLACEMENT);
        result = MAILTO.matcher(result).replaceAll(MAILTO_REPLACEMENT);
        return WWW_PREFIX.matcher(result).replaceAll(WWW_PREFIX_REPLACEMENT);
    }
}
