package com.seamware.consentmanager.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A catalog self-description, as the catalog serves it for a service offering or a data resource.
 *
 * <p>One record for both, because the walk a resolved retrieval performs reads the same three
 * members off either document and differs only in which one it is reading: an offering is the one
 * that names {@code dataResources}, a resource names none. Every member is nullable and unknown
 * ones are ignored, so a catalog that serves more than this does not break the read.
 *
 * <p>{@code dataResources} is deliberately untyped. No real catalog document was available to this
 * ticket, and the two shapes a catalog plausibly serves - a bare URL string, or an object naming
 * the resource under {@code @id} / {@code _id} / {@code url} - decode into different Java types; a
 * record committed to one of them would throw on the other, and a decode failure here is silent and
 * total, reporting every notice's data section unavailable forever. Both forms are therefore
 * accepted and {@link #dataResourceUrls()} is the only place the shape is interpreted.
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
     * The data-resource documents this one names, empty when it names none.
     *
     * <p>An entry in neither accepted shape, or in one carrying no usable URL, is dropped rather
     * than failing the read: the rest of the list is still worth resolving, and the section reports
     * itself unavailable anyway once a resource it names cannot be read.
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

    /** That value when it says something, null when it is blank. */
    @Nullable
    private static String nonBlank(String value) {
        return value.isBlank() ? null : value;
    }
}
