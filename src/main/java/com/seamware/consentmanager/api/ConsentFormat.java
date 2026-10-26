package com.seamware.consentmanager.api;

import com.seamware.consentmanager.error.BadRequestException;
import io.micronaut.core.annotation.Nullable;
import java.util.Arrays;
import java.util.stream.Collectors;

/** The representations a consent read's {@code format} query parameter selects between. */
public enum ConsentFormat {
    FULL("full"),
    RECEIPT("receipt"),
    RECEIPT_KANTARA("receipt-kantara");

    private static final String UNKNOWN = "Unknown format '%s'; expected one of: %s.";

    private static final String LISTING_FULL_ONLY =
            "A listing renders format 'full' alone; read a receipt one consent at a time.";

    private static final String RECEIPT_WITHOUT_LINEAGE =
            "A receipt is evidence of one decision and carries no lineage; drop includeLineage or"
                    + " read format 'full'.";

    private final String value;

    ConsentFormat(String value) {
        this.value = value;
    }

    /** The format {@code value} names; absent means {@link #FULL}, unknown is a {@code 400}. */
    public static ConsentFormat of(@Nullable String value) {
        if (value == null) {
            return FULL;
        }
        return Arrays.stream(values())
                .filter(format -> format.value.equals(value))
                .findFirst()
                .orElseThrow(
                        () ->
                                new BadRequestException(
                                        UNKNOWN.formatted(
                                                value,
                                                Arrays.stream(values())
                                                        .map(format -> format.value)
                                                        .collect(Collectors.joining(", ")))));
    }

    /** Admits {@link #FULL} alone, the only format a listing renders; else a {@code 400}. */
    public static void requireListable(@Nullable String value) {
        if (of(value) != FULL) {
            throw new BadRequestException(LISTING_FULL_ONLY);
        }
    }

    /** The format {@code value} names; a receipt asked for with lineage is a {@code 400}. */
    public static ConsentFormat of(@Nullable String value, boolean includeLineage) {
        ConsentFormat format = of(value);
        if (includeLineage && format != FULL) {
            throw new BadRequestException(RECEIPT_WITHOUT_LINEAGE);
        }
        return format;
    }
}
