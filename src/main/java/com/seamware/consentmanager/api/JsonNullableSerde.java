package com.seamware.consentmanager.api;

import io.micronaut.core.type.Argument;
import io.micronaut.serde.Decoder;
import io.micronaut.serde.Encoder;
import io.micronaut.serde.Serde;
import io.micronaut.serde.Serializer;
import jakarta.inject.Singleton;
import java.io.IOException;
import org.openapitools.jackson.nullable.JsonNullable;

/**
 * Teaches micronaut-serde the {@link JsonNullable} wrapper the generator emits for a property
 * declared {@code nullable} in the specification.
 *
 * <p>It exists so a {@code PATCH} body can tell an omitted property from one explicitly set to
 * {@code null}: an absent property decodes to {@link JsonNullable#undefined()} and an explicit
 * {@code null} to a defined wrapper holding {@code null}. Serialisation unwraps again, so a
 * response never shows the wrapper itself.
 */
@Singleton
public class JsonNullableSerde implements Serde<JsonNullable<Object>> {

    @Override
    public JsonNullable<Object> deserialize(
            Decoder decoder, DecoderContext context, Argument<? super JsonNullable<Object>> type)
            throws IOException {
        Argument<Object> valueType = valueType(type);
        return JsonNullable.of(
                context.findDeserializer(valueType)
                        .createSpecific(context, valueType)
                        .deserialize(decoder, context, valueType));
    }

    /** An explicit {@code null} is a value here, not an absence — that is the whole point. */
    @Override
    public JsonNullable<Object> deserializeNullable(
            Decoder decoder, DecoderContext context, Argument<? super JsonNullable<Object>> type)
            throws IOException {
        if (decoder.decodeNull()) {
            return JsonNullable.of(null);
        }
        return deserialize(decoder, context, type);
    }

    /** What an omitted property leaves behind: undefined, which is neither a value nor a null. */
    @Override
    public JsonNullable<Object> getDefaultValue(
            DecoderContext context, Argument<? super JsonNullable<Object>> type) {
        return JsonNullable.undefined();
    }

    @Override
    @SuppressWarnings("unchecked")
    public void serialize(
            Encoder encoder,
            EncoderContext context,
            Argument<? extends JsonNullable<Object>> type,
            JsonNullable<Object> value)
            throws IOException {
        Object unwrapped = value == null ? null : value.orElse(null);
        if (unwrapped == null) {
            encoder.encodeNull();
            return;
        }
        Argument<Object> valueType = valueType(type);
        ((Serializer<Object>) context.findSerializer(valueType))
                .createSpecific(context, valueType)
                .serialize(encoder, context, valueType, unwrapped);
    }

    @Override
    public boolean isAbsent(EncoderContext context, JsonNullable<Object> value) {
        return value == null || !value.isPresent();
    }

    @Override
    public boolean isEmpty(EncoderContext context, JsonNullable<Object> value) {
        return isAbsent(context, value);
    }

    /** The wrapped type, defaulting to {@code Object} for a raw {@code JsonNullable}. */
    @SuppressWarnings("unchecked")
    private static Argument<Object> valueType(Argument<?> type) {
        Argument<?>[] parameters = type.getTypeParameters();
        return parameters.length == 0 ? Argument.OBJECT_ARGUMENT : (Argument<Object>) parameters[0];
    }
}
