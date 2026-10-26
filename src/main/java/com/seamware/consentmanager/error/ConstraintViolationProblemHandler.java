package com.seamware.consentmanager.error;

import com.seamware.consentmanager.api.generated.model.ProblemDetail;
import com.seamware.consentmanager.api.generated.model.ProblemFieldError;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.core.annotation.Nullable;
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
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

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

    /** Orders the published rejections so the same request always reads the same. */
    private static final Comparator<ProblemFieldError> BY_FIELD_THEN_MESSAGE =
            Comparator.comparing(ProblemFieldError::getField)
                    .thenComparing(ProblemFieldError::getMessage);

    /**
     * Maps every violation to a single 400 listing each rejected field in {@code errors} and,
     * joined, in {@code detail}.
     */
    @Override
    public HttpResponse<?> handle(HttpRequest request, ConstraintViolationException exception) {
        List<ProblemFieldError> errors = fieldErrorsOf(exception.getConstraintViolations());
        ProblemDetail problem =
                ProblemType.BAD_REQUEST.toProblemDetail(describe(errors), request.getPath());
        if (!errors.isEmpty()) {
            problem.setErrors(errors);
        }
        return HttpResponse.status(ProblemType.BAD_REQUEST.status())
                .body(problem)
                .contentType(MediaType.of(ProblemType.MEDIA_TYPE));
    }

    /** One entry per distinct rejection, sorted by field and then message. */
    private static List<ProblemFieldError> fieldErrorsOf(
            @Nullable Set<? extends ConstraintViolation<?>> violations) {
        if (violations == null) {
            return List.of();
        }
        return violations.stream()
                .map(
                        violation ->
                                new ProblemFieldError(
                                        leafOf(violation.getPropertyPath()),
                                        violation.getMessage()))
                .distinct()
                .sorted(BY_FIELD_THEN_MESSAGE)
                .toList();
    }

    /** Joins the rejections into one sentence, or says that none was named. */
    private static String describe(List<ProblemFieldError> errors) {
        if (errors.isEmpty()) {
            return UNSPECIFIED_DETAIL;
        }
        return errors.stream()
                .map(error -> error.getField() + FIELD_SEPARATOR + error.getMessage())
                .collect(Collectors.joining(VIOLATION_SEPARATOR));
    }

    /** The last named node of a property path, or the whole path when no node is named. */
    private static String leafOf(Path propertyPath) {
        return StreamSupport.stream(propertyPath.spliterator(), false)
                .map(Path.Node::getName)
                .filter(Objects::nonNull)
                .reduce((earlier, later) -> later)
                .orElseGet(propertyPath::toString);
    }
}
