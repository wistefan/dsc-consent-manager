package com.seamware.consentmanager.domain;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Utility class for generating UUIDv7 identifiers.
 *
 * <p>UUIDv7 (RFC 9562) embeds a Unix-epoch millisecond timestamp in the most significant 48 bits,
 * followed by a version nibble (0x7), random data, a variant marker (0b10), and more random data.
 * The timestamp prefix ensures that UUIDv7 values sort chronologically in B-tree indexes, providing
 * better insert locality than the fully random UUIDv4.
 *
 * <p>This implementation uses {@link SecureRandom} for the random bits and {@link
 * System#currentTimeMillis()} for the timestamp component.
 *
 * <p>Thread safety: this class is safe for use by multiple threads. The underlying {@link
 * SecureRandom} instance is thread-safe.
 */
public final class UuidGenerator {

    /** Bit mask for the UUID version field (bits 48–51): version 7. */
    private static final long VERSION_7_MASK = 0x7000L;

    /** Bit mask to clear the version nibble (bits 48–51) before setting it. */
    private static final long VERSION_CLEAR_MASK = 0xFFFF_FFFF_FFFF_0FFFL;

    /** Bit mask for the UUID variant field (bits 64–65): variant 10 (RFC 9562). */
    private static final long VARIANT_RFC_MASK = 0x8000_0000_0000_0000L;

    /** Bit mask to clear the variant bits (top 2 bits of the lower 64) before setting them. */
    private static final long VARIANT_CLEAR_MASK = 0x3FFF_FFFF_FFFF_FFFFL;

    /** Number of bits to shift the millisecond timestamp into the upper portion of the MSB. */
    private static final int TIMESTAMP_SHIFT = 16;

    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidGenerator() {
        // Utility class — no instantiation
    }

    /**
     * Generates a new UUIDv7 with a millisecond-precision timestamp and cryptographically strong
     * random bits.
     *
     * <p>The layout follows RFC 9562 Section 5.7:
     *
     * <pre>
     *  0                   1                   2                   3
     *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
     * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     * |                         unix_ts_ms (32 high bits)            |
     * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     * |   unix_ts_ms (16 low bits)    |  ver  |  rand_a (12 bits)    |
     * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     * |var|              rand_b (62 bits)                             |
     * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     * |                         rand_b (continued)                   |
     * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
     * </pre>
     *
     * @return a new UUIDv7 instance
     */
    public static UUID uuidV7() {
        long timestamp = System.currentTimeMillis();

        // Most significant bits: 48-bit timestamp | version 7 | 12 random bits
        long msb = (timestamp << TIMESTAMP_SHIFT) & VERSION_CLEAR_MASK;
        msb |= VERSION_7_MASK;
        msb |= (RANDOM.nextLong() & 0x0FFFL);

        // Least significant bits: variant 10 | 62 random bits
        long lsb = (RANDOM.nextLong() & VARIANT_CLEAR_MASK) | VARIANT_RFC_MASK;

        return new UUID(msb, lsb);
    }
}
