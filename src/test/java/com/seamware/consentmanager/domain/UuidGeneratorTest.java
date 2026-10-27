package com.seamware.consentmanager.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/** Pins that {@link UuidGenerator} produces unique, time-ordered RFC 9562 UUIDv7 values. */
class UuidGeneratorTest {

    /** UUIDv7 version number (bits 48–51 of the most-significant long). */
    private static final int EXPECTED_VERSION = 7;

    /** RFC 9562 variant value (bits 64–65 = 10 → variant() returns 2). */
    private static final int EXPECTED_VARIANT = 2;

    private static final int BATCH_SIZE = 1000;

    @Test
    void uuidV7_shouldReturnVersion7() {
        UUID uuid = UuidGenerator.uuidV7();

        assertThat(uuid.version()).isEqualTo(EXPECTED_VERSION);
    }

    @Test
    void uuidV7_shouldReturnRfc9562Variant() {
        UUID uuid = UuidGenerator.uuidV7();

        assertThat(uuid.variant()).isEqualTo(EXPECTED_VARIANT);
    }

    @Test
    void uuidV7_shouldEmbedCurrentTimestamp() {
        long beforeMillis = System.currentTimeMillis();
        UUID uuid = UuidGenerator.uuidV7();
        long afterMillis = System.currentTimeMillis();

        // The top 48 bits are the Unix millisecond timestamp.
        long embeddedTimestamp = uuid.getMostSignificantBits() >>> 16;

        assertThat(embeddedTimestamp)
                .as("Embedded timestamp should be between before and after generation")
                .isGreaterThanOrEqualTo(beforeMillis)
                .isLessThanOrEqualTo(afterMillis);
    }

    @Test
    void uuidV7_shouldProduceUniqueValues() {
        Set<UUID> generated =
                IntStream.range(0, BATCH_SIZE)
                        .mapToObj(i -> UuidGenerator.uuidV7())
                        .collect(Collectors.toSet());

        assertThat(generated).hasSize(BATCH_SIZE);
    }

    @RepeatedTest(10)
    void uuidV7_shouldMaintainChronologicalOrdering() {
        UUID first = UuidGenerator.uuidV7();
        UUID second = UuidGenerator.uuidV7();

        long firstTimestamp = first.getMostSignificantBits() >>> 16;
        long secondTimestamp = second.getMostSignificantBits() >>> 16;

        assertThat(secondTimestamp)
                .as("Second UUID should have an equal or later timestamp than the first")
                .isGreaterThanOrEqualTo(firstTimestamp);
    }

    @RepeatedTest(10)
    void uuidV7_shouldAlwaysHaveCorrectVersionAndVariant() {
        UUID uuid = UuidGenerator.uuidV7();

        assertThat(uuid.version()).isEqualTo(EXPECTED_VERSION);
        assertThat(uuid.variant()).isEqualTo(EXPECTED_VARIANT);
    }
}
