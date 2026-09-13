package com.pulse.boundary.filter;

/**
 * Unchecked exception thrown when a JWT signature is invalid or token is malformed.
 */
public class InvalidTokenException extends RuntimeException {

    public InvalidTokenException(String message) {
        super(message);
    }

    public InvalidTokenException(String message, Throwable cause) {
        super(message, cause);
    }
}
