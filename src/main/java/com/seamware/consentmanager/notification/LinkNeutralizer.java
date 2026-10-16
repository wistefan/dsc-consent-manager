package com.seamware.consentmanager.notification;

import java.util.regex.Pattern;

/**
 * Breaks the URL shapes mail clients autolink, so a value set by another party cannot become a
 * clickable link in a mail from this service's sender. A bare domain is deliberately left intact.
 */
final class LinkNeutralizer {

    /** {@code <scheme>://} for any scheme, matched case-insensitively. */
    static final Pattern SCHEME_SEPARATOR =
            Pattern.compile("\\b([a-z][a-z0-9+.-]*)://", Pattern.CASE_INSENSITIVE);

    static final String SCHEME_SEPARATOR_REPLACEMENT = "$1[:]//";

    static final Pattern MAILTO = Pattern.compile("\\b(mailto):", Pattern.CASE_INSENSITIVE);
    static final String MAILTO_REPLACEMENT = "$1[:]";

    /** {@code www.} at the start of a word. */
    static final Pattern WWW_PREFIX = Pattern.compile("\\b(www)\\.", Pattern.CASE_INSENSITIVE);

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
