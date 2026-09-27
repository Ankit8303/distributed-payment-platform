package com.paymentledger.shared.error;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Standard RFC 7807 problem details response model.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiErrorResponse {

    private String type;
    private String title;
    private int status;
    private String detail;
    private String instance;
    private String errorCode;
    private String correlationId;

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Instant timestamp;

    private List<InvalidParameter> invalidParameters;

    public ApiErrorResponse() {
        this.timestamp = Instant.now();
        this.invalidParameters = new ArrayList<>();
    }

    public ApiErrorResponse(String type, String title, int status, String detail,
                            String instance, String errorCode, String correlationId) {
        this.type = type;
        this.title = title;
        this.status = status;
        this.detail = detail;
        this.instance = instance;
        this.errorCode = errorCode;
        this.correlationId = correlationId;
        this.timestamp = Instant.now();
        this.invalidParameters = new ArrayList<>();
    }

    public static class InvalidParameter {
        private String field;
        private String reason;

        public InvalidParameter() {
        }

        public InvalidParameter(String field, String reason) {
            this.field = field;
            this.reason = reason;
        }

        public String getField() {
            return field;
        }

        public void setField(String field) {
            this.field = field;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public String getInstance() {
        return instance;
    }

    public void setInstance(String instance) {
        this.instance = instance;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public List<InvalidParameter> getInvalidParameters() {
        return invalidParameters;
    }

    public void setInvalidParameters(List<InvalidParameter> invalidParameters) {
        this.invalidParameters = invalidParameters;
    }
}
