package com.aesoftwaresolutions.solid.common;

/** The caller is known but not allowed to do this. Maps to HTTP 403. */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
