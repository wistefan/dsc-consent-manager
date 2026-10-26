package com.seamware.consentmanager.signing;

import io.micronaut.core.annotation.NonNull;
import io.micronaut.json.JsonMapper;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.erdtman.jcs.JsonCanonicalizer;

/**
 * The RFC 8785 (JCS) form of a value: the bytes a receipt signature covers. Throws {@link
 * UncheckedIOException} when the value does not serialise or the text is not JSON.
 */
@Singleton
public class CanonicalJsonSerializer {

    private final JsonMapper json;

    public CanonicalJsonSerializer(JsonMapper json) {
        this.json = json;
    }

    /** The canonical UTF-8 bytes of {@code value} as the application serialises it. */
    @NonNull
    public byte[] toBytes(@NonNull Object value) {
        try {
            return new JsonCanonicalizer(json.writeValueAsBytes(value)).getEncodedUTF8();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not canonicalize a value", e);
        }
    }

    /** The canonical text of {@code value} as the application serialises it. */
    @NonNull
    public String toJson(@NonNull Object value) {
        return new String(toBytes(value), StandardCharsets.UTF_8);
    }

    /** The canonical text of raw JSON, whatever produced it. */
    @NonNull
    public String canonicalize(@NonNull String rawJson) {
        try {
            return new JsonCanonicalizer(rawJson).getEncodedString();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not canonicalize JSON text", e);
        }
    }
}
