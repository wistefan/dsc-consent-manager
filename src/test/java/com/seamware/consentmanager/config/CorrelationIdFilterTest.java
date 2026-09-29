package com.seamware.consentmanager.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.simple.SimpleHttpResponseFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * Unit tests for {@link CorrelationIdFilter}.
 *
 * <p>Verifies that the filter correctly extracts or generates correlation IDs, sets them in the
 * MDC, propagates them in response headers, and cleans up the MDC after the response.
 */
class CorrelationIdFilterTest {

    private static final String EXISTING_CORRELATION_ID = "test-corr-id-12345";

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("onRequest sets provided X-Correlation-ID into MDC")
    void onRequest_withHeader_setsMdc() {
        HttpRequest<?> request =
                HttpRequest.GET("/test")
                        .header(CorrelationIdFilter.CORRELATION_ID_HEADER, EXISTING_CORRELATION_ID);

        filter.onRequest(request);

        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isEqualTo(EXISTING_CORRELATION_ID);
    }

    @Test
    @DisplayName("onRequest generates UUID when X-Correlation-ID header is absent")
    void onRequest_withoutHeader_generatesUuid() {
        HttpRequest<?> request = HttpRequest.GET("/test");

        filter.onRequest(request);

        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        assertThat(correlationId).isNotNull().isNotBlank();
        // UUID format: 8-4-4-4-12 hex digits
        assertThat(correlationId)
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    @DisplayName("onResponse propagates correlation ID in response header")
    void onResponse_propagatesHeader() {
        MDC.put(CorrelationIdFilter.MDC_KEY, EXISTING_CORRELATION_ID);
        MutableHttpResponse<?> response = SimpleHttpResponseFactory.INSTANCE.ok();

        filter.onResponse(response);

        assertThat(response.header(CorrelationIdFilter.CORRELATION_ID_HEADER))
                .isEqualTo(EXISTING_CORRELATION_ID);
    }

    @Test
    @DisplayName("onResponse clears MDC after processing")
    void onResponse_clearsMdc() {
        MDC.put(CorrelationIdFilter.MDC_KEY, EXISTING_CORRELATION_ID);
        MutableHttpResponse<?> response = SimpleHttpResponseFactory.INSTANCE.ok();

        filter.onResponse(response);

        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    @DisplayName("onResponse handles missing MDC entry gracefully")
    void onResponse_withoutMdcEntry_doesNotSetHeader() {
        MutableHttpResponse<?> response = SimpleHttpResponseFactory.INSTANCE.ok();

        filter.onResponse(response);

        assertThat(response.header(CorrelationIdFilter.CORRELATION_ID_HEADER)).isNull();
    }

    @Test
    @DisplayName("Full request-response cycle preserves correlation ID")
    void fullCycle_preservesCorrelationId() {
        HttpRequest<?> request =
                HttpRequest.GET("/test")
                        .header(CorrelationIdFilter.CORRELATION_ID_HEADER, EXISTING_CORRELATION_ID);

        filter.onRequest(request);
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isEqualTo(EXISTING_CORRELATION_ID);

        MutableHttpResponse<?> response = SimpleHttpResponseFactory.INSTANCE.ok();
        filter.onResponse(response);

        assertThat(response.header(CorrelationIdFilter.CORRELATION_ID_HEADER))
                .isEqualTo(EXISTING_CORRELATION_ID);
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }
}
