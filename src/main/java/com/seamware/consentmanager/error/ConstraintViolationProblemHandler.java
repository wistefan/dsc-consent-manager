package com.seamware.consentmanager.error;

import io.micronaut.context.annotation.Replaces;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import io.micronaut.validation.exceptions.ConstraintExceptionHandler;
import jakarta.inject.Singleton;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import java.util.Comparator;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Renders a bean-validation failure on a generated request model as a 400 problem detail.
 *
 * <p>Replaces {@link ConstraintExceptionHandler} rather than sitting beside it: two handlers for
 * one exception type is ambiguous bean resolution, and the built-in one emits a Hateoas error body
 * that no other failure of this service looks like.
 *
 * <p>Only the leaf of each property path is published. The full path is prefixed with the
 * controller method and parameter names, which are an implementation detail the caller cannot act
 * on and should not learn.
 */
@Singleton
@Produces(ProblemType.MEDIA_TYPE)
@Replaces(ConstraintExceptionHandler.class)
public class ConstraintViolationProblemHandler
        implements ExceptionHandler<ConstraintViolationException, HttpResponse<?>> {

    /** Separator between the individual violations listed in one detail string. */
    private static final String VIOLATION_SEPARATOR = "; ";

    /** Separator between a violated field and the reason it was rejected. */
    private static final String FIELD_SEPARATOR = ": ";

    /** Detail used when the exception carries no violation to describe. */
    private static final String UNSPECIFIED_DETAIL = "The request failed validation.";

    /** Maps every violation to a single 400 whose detail names the rejected fields. */
    @Override
    public HttpResponse<?> handle(HttpRequest request, ConstraintViolationException exception) {
        return HttpResponse.status(ProblemType.BAD_REQUEST.status())
                .body(
                        ProblemType.BAD_REQUEST.toProblemDetail(
                                describe(exception.getConstraintViolations()), request.getPath()))
                .contentType(MediaType.of(ProblemType.MEDIA_TYPE));
    }

    /** Lists the violations in a stable order so the same request always reads the same. */
    private static String describe(Set<? extends ConstraintViolation<?>> violations) {
        if (violations == null || violations.isEmpty()) {
            return UNSPECIFIED_DETAIL;
        }
        return violations.stream()
                .map(
                        violation ->
                                leafOf(violation.getPropertyPath())
                                        + FIELD_SEPARATOR
                                        + violation.getMessage())
                .distinct()
                .sorted(Comparator.naturalOrder())
                .collect(Collectors.joining(VIOLATION_SEPARATOR));
    }

    /** The last named node of a property path, or the whole path when no node is named. */
    private static String leafOf(Path propertyPath) {
        String leaf = null;
        for (Path.Node node : propertyPath) {
            if (node.getName() != null) {
                leaf = node.getName();
            }
        }
        return leaf != null ? leaf : propertyPath.toString();
    }
}
