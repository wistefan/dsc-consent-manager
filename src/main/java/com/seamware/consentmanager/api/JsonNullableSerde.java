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
 * Serde for the generated {@link JsonNullable}, so a {@code PATCH} body tells an omitted property
 * ({@link JsonNullable#undefined()}) from an explicit {@code null}. Serialisation unwraps.
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

    /** An explicit {@code null} is a value here, not an absence. */
    @Override
    public JsonNullable<Object> deserializeNullable(
            Decoder decoder, DecoderContext context, Argument<? super JsonNullable<Object>> type)
            throws IOException {
        if (decoder.decodeNull()) {
            return JsonNullable.of(null);
        }
        return deserialize(decoder, context, type);
    }

    /** An omitted property is undefined: neither a value nor a null. */
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
