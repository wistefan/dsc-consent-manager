package com.seamware.consentmanager.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import java.util.List;
import java.util.Objects;

/**
 * A catalog self-description, as the catalog serves it for a service offering or a data resource.
 *
 * <p>One record for both, because the walk a resolved retrieval performs reads the same three
 * members off either document and differs only in which one it is reading: an offering is the one
 * that names {@code dataResources}, a resource names none. Every member is nullable and unknown
 * ones are ignored, so a catalog that serves more than this does not break the read.
 */
@Serdeable
public record SelfDescription(
        @JsonProperty("_id") @Nullable String id,
        @Nullable String name,
        @Nullable String description,
        @Nullable List<String> dataResources) {

    /** The data-resource documents this one names, empty when it names none. */
    @NonNull
    public List<String> dataResourceUrls() {
        return dataResources == null
                ? List.of()
                : dataResources.stream().filter(Objects::nonNull).toList();
    }
}
