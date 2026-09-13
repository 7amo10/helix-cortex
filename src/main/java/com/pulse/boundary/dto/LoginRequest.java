package com.pulse.boundary.dto;

/**
 * DTO for authentication login request.
 */
public record LoginRequest(String username, String password) {
}
