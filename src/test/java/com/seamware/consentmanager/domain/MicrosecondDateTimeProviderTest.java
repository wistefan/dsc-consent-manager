package com.seamware.consentmanager.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;

@DisplayName("Entity timestamps")
class MicrosecondDateTimeProviderTest {

    /** Enough samples that a clock with nanosecond digits would be hit by at least one. */
    private static final int SAMPLES = 50;

    private final MicrosecondDateTimeProvider provider = new MicrosecondDateTimeProvider();

    @RepeatedTest(SAMPLES)
    @DisplayName("carry no digit finer than the microsecond PostgreSQL stores")
    void areAlignedToMicroseconds() {
        OffsetDateTime now = provider.getNow();
        assertThat(now).isEqualTo(now.truncatedTo(ChronoUnit.MICROS));
    }
}
