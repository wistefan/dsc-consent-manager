package com.seamware.consentmanager.error;

import com.seamware.consentmanager.config.ConsentManagerConfiguration;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;

/**
 * Chooses the {@code detail} a server-side failure publishes, so every 5xx site applies the same
 * {@code consent-manager.errors.expose-exception-detail} rule. A stack trace is never published.
 */
@Singleton
public class ServerErrorDetail {

    private final boolean exposeExceptionDetail;

    /** Creates the helper from the deployment's error settings. */
    public ServerErrorDetail(ConsentManagerConfiguration.Errors errors) {
        this.exposeExceptionDetail = errors.isExposeExceptionDetail();
    }

    /**
     * The exception's own message when exposure is on and it has one, otherwise {@link
     * ProblemType#SERVER_ERROR_DETAIL}.
     */
    public String detailFor(@Nullable Throwable failure) {
        String message = failure == null ? null : failure.getMessage();
        return exposeExceptionDetail && message != null && !message.isBlank()
                ? message
                : ProblemType.SERVER_ERROR_DETAIL;
    }
}
