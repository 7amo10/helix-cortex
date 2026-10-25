package com.pulse.boundary.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Structured error response payload returned when an untrusted or sandboxed rule
 * violates AST capability policies (HTTP 422 Unprocessable Entity).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CapabilityViolationResponse(
        int status,
        String title,
        String violatingClass,
        String violatingMethod,
        String errorMessage,
        String detail
) {

    public CapabilityViolationResponse(String violatingClass, String violatingMethod, String errorMessage) {
        this(422, "Unprocessable Entity", violatingClass, violatingMethod, errorMessage, errorMessage);
    }

    public String getViolatingClass() {
        return violatingClass;
    }

    public String getViolatingMethod() {
        return violatingMethod;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}
