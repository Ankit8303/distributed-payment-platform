package com.paymentledger.shared.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    @DisplayName("Should preserve incoming X-Correlation-ID header and populate MDC")
    void shouldPreserveIncomingCorrelationId() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        String incomingId = "client-trace-" + UUID.randomUUID();
        request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, incomingId);

        AtomicReference<String> mdcDuringFilter = new AtomicReference<>();
        FilterChain chain = (req, res) -> mdcDuringFilter.set(MDC.get(CorrelationIdFilter.MDC_KEY));

        filter.doFilter(request, response, chain);

        assertThat(mdcDuringFilter.get()).isEqualTo(incomingId);
        assertThat(response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER)).isEqualTo(incomingId);
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    @DisplayName("Should generate new UUID correlation ID when header is missing")
    void shouldGenerateNewCorrelationIdWhenMissing() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<String> mdcDuringFilter = new AtomicReference<>();
        FilterChain chain = (req, res) -> mdcDuringFilter.set(MDC.get(CorrelationIdFilter.MDC_KEY));

        filter.doFilter(request, response, chain);

        String generatedId = mdcDuringFilter.get();
        assertThat(generatedId).isNotBlank();
        assertThat(UUID.fromString(generatedId)).isNotNull();
        assertThat(response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER)).isEqualTo(generatedId);
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }
}
