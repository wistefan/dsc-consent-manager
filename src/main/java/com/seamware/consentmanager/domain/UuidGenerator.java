package com.seamware.consentmanager.domain;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Generates RFC 9562 UUIDv7 ids, whose millisecond prefix sorts chronologically for B-tree insert
 * locality; thread-safe, using {@link SecureRandom} for the random bits.
 */
public final class UuidGenerator {

    /** Version field (bits 48-51) set to 7. */
    private static final long VERSION_7_MASK = 0x7000L;

    /** Clears the version nibble (bits 48-51). */
    private static final long VERSION_CLEAR_MASK = 0xFFFF_FFFF_FFFF_0FFFL;

    /** Variant field (bits 64-65) set to {@code 10}. */
    private static final long VARIANT_RFC_MASK = 0x8000_0000_0000_0000L;

    /** Clears the variant bits (top two of the lower 64). */
    private static final long VARIANT_CLEAR_MASK = 0x3FFF_FFFF_FFFF_FFFFL;

    /** Shift placing the 48-bit millisecond timestamp at the top of the MSB. */
    private static final int TIMESTAMP_SHIFT = 16;

    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidGenerator() {}

    /** A new UUIDv7 from the current millisecond and cryptographically strong random bits. */
    public static UUID uuidV7() {
        long timestamp = System.currentTimeMillis();

        // MSB: 48-bit timestamp | version 7 | 12 random bits
        long msb = (timestamp << TIMESTAMP_SHIFT) & VERSION_CLEAR_MASK;
        msb |= VERSION_7_MASK;
        msb |= (RANDOM.nextLong() & 0x0FFFL);

        // LSB: variant 10 | 62 random bits
        long lsb = (RANDOM.nextLong() & VARIANT_CLEAR_MASK) | VARIANT_RFC_MASK;

        return new UUID(msb, lsb);
    }
}
