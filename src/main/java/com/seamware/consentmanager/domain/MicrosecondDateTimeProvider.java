package com.seamware.consentmanager.domain;

import io.micronaut.context.annotation.Replaces;
import io.micronaut.data.runtime.date.CurrentDateTimeProvider;
import io.micronaut.data.runtime.date.DateTimeProvider;
import jakarta.inject.Singleton;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * Stamps {@code @DateCreated} and {@code @DateUpdated} at the resolution PostgreSQL keeps.
 *
 * <p>The default provider reads the clock at nanosecond precision, which {@code timestamptz}
 * silently drops to microseconds on write. The entity left in memory after an insert then carries
 * digits the stored row does not, so a response rendered from it differs from every later response
 * rendered after a read. Aligning the stamp to microseconds up front makes the two identical.
 *
 * <p>Stamped at UTC rather than the host's zone, so a column mapped as an offset type would carry
 * the same offset everywhere the service runs.
 */
@Singleton
@Replaces(CurrentDateTimeProvider.class)
public class MicrosecondDateTimeProvider implements DateTimeProvider<OffsetDateTime> {

    @Override
    public OffsetDateTime getNow() {
        return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
