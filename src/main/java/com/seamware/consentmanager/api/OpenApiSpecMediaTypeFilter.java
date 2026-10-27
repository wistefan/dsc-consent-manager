package com.seamware.consentmanager.api;

import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.ResponseFilter;
import io.micronaut.http.annotation.ServerFilter;

/**
 * Labels the served specification as YAML, which Micronaut's extension table does not know and
 * would send as {@code text/plain}. Only a successful response is relabelled, so an error keeps its
 * problem-detail type.
 */
@ServerFilter(OpenApiSpecMediaTypeFilter.SPEC_PATH)
public class OpenApiSpecMediaTypeFilter {

    /** Where the static-resource mapping serves the bundled specification. */
    public static final String SPEC_PATH = "/openapi.yaml";

    /** Sets the YAML content type on the specification's successful response. */
    @ResponseFilter
    public void labelAsYaml(MutableHttpResponse<?> response) {
        if (response.code() == HttpStatus.OK.getCode()) {
            response.contentType(MediaType.APPLICATION_YAML_TYPE);
        }
    }
}
