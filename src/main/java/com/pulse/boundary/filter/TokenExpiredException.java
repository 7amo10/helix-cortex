package com.pulse.boundary.filter;

/**
 * Unchecked exception thrown when a JWT token's exp claim has elapsed.
 */
public class TokenExpiredException extends RuntimeException {

    public TokenExpiredException(String message) {
        super(message);
    }
}
