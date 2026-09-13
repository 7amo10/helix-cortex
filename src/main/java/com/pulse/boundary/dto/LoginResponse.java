package com.pulse.boundary.dto;

import com.pulse.entity.EngineRole;

/**
 * DTO returned upon successful login containing JWT token and metadata.
 */
public record LoginResponse(String token, EngineRole role, long expiresIn) {
}
