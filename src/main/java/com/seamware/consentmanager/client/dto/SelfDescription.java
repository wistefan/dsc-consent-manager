package com.seamware.consentmanager.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A catalog self-description of a service offering (names {@code dataResources}) or a data resource
 * (names none). Every member is nullable and unknown ones are ignored.
 *
 * <p>{@code dataResources} is untyped on purpose: an entry may be a URL string or an object, and
 * {@link #dataResourceUrls()} is the only place the shape is interpreted.
 */
@Serdeable
public record SelfDescription(
        @JsonProperty("_id") @Nullable String id,
        @Nullable String name,
        @Nullable String description,
        @Nullable List<Object> dataResources) {

    /** Members a resource object may name itself by, in the order they are read for. */
    private static final List<String> RESOURCE_URL_MEMBERS =
            List.of("@id", "_id", "id", "url", "identifier");

    /**
     * The data-resource URLs this document names, empty when none; an entry without a usable URL is
     * dropped rather than failing the read.
     */
    @NonNull
    public List<String> dataResourceUrls() {
        return dataResources == null
                ? List.of()
                : dataResources.stream()
                        .map(SelfDescription::urlOf)
                        .filter(Objects::nonNull)
                        .toList();
    }

    /** The URL an entry stands for, in either accepted shape; null when it carries none. */
    @Nullable
    private static String urlOf(@Nullable Object entry) {
        return switch (entry) {
            case String url -> nonBlank(url);
            case Map<?, ?> document ->
                    RESOURCE_URL_MEMBERS.stream()
                            .map(document::get)
                            .filter(String.class::isInstance)
                            .map(member -> nonBlank((String) member))
                            .filter(Objects::nonNull)
                            .findFirst()
                            .orElse(null);
            case null, default -> null;
        };
    }

    /** Null when blank. */
    @Nullable
    private static String nonBlank(String value) {
        return value.isBlank() ? null : value;
    }
}
