package com.paymentledger.shared.error;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GlobalExceptionHandlerTest {

    @InjectMocks
    private GlobalExceptionHandler exceptionHandler;

    @Mock
    private HttpServletRequest request;

    @Test
    @DisplayName("Should return RFC 7807 problem details for generic unhandled exception")
    void shouldReturnRfc7807ForGenericException() {
        when(request.getRequestURI()).thenReturn("/api/v1/payments");
        MDC.put("correlationId", "test-corr-12345");

        try {
            Exception ex = new RuntimeException("Unexpected database failure");
            ResponseEntity<ApiErrorResponse> response = exceptionHandler.handleGenericException(ex, request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody()).isNotNull();

            ApiErrorResponse body = response.getBody();
            assertThat(body.getType()).isEqualTo("https://api.paymentledger.com/errors/INTERNAL_SERVER_ERROR");
            assertThat(body.getTitle()).isEqualTo("An unexpected internal server error occurred");
            assertThat(body.getStatus()).isEqualTo(500);
            assertThat(body.getDetail()).isEqualTo("An unexpected internal server error occurred");
            assertThat(body.getInstance()).isEqualTo("/api/v1/payments");
            assertThat(body.getErrorCode()).isEqualTo("INTERNAL_SERVER_ERROR");
            assertThat(body.getCorrelationId()).isEqualTo("test-corr-12345");
            assertThat(body.getTimestamp()).isNotNull();
        } finally {
            MDC.clear();
        }
    }

    @Test
    @DisplayName("Should return RFC 7807 problem details for ResponseStatusException")
    void shouldReturnRfc7807ForResponseStatusException() {
        when(request.getRequestURI()).thenReturn("/api/v1/accounts/123");
        MDC.put("correlationId", "test-corr-67890");

        try {
            ResponseStatusException ex = new ResponseStatusException(HttpStatus.NOT_FOUND, "Account does not exist");
            ResponseEntity<ApiErrorResponse> response = exceptionHandler.handleResponseStatusException(ex, request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody()).isNotNull();

            ApiErrorResponse body = response.getBody();
            assertThat(body.getStatus()).isEqualTo(404);
            assertThat(body.getErrorCode()).isEqualTo("NOT_FOUND");
            assertThat(body.getDetail()).isEqualTo("Account does not exist");
            assertThat(body.getInstance()).isEqualTo("/api/v1/accounts/123");
            assertThat(body.getCorrelationId()).isEqualTo("test-corr-67890");
        } finally {
            MDC.clear();
        }
    }
}
