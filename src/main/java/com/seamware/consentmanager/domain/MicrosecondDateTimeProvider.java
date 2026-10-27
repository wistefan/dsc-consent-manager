package com.seamware.consentmanager.domain;

import io.micronaut.context.annotation.Replaces;
import io.micronaut.data.runtime.date.CurrentDateTimeProvider;
import io.micronaut.data.runtime.date.DateTimeProvider;
import jakarta.inject.Singleton;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * Stamps {@code @DateCreated}/{@code @DateUpdated} in UTC at microsecond precision, the resolution
 * {@code timestamptz} keeps, so an entity just written renders the same as one read back.
 */
@Singleton
@Replaces(CurrentDateTimeProvider.class)
public class MicrosecondDateTimeProvider implements DateTimeProvider<OffsetDateTime> {

    @Override
    public OffsetDateTime getNow() {
        return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
