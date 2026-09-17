package com.aesoftwaresolutions.solid.common;

/** The requested resource does not exist (or is not visible to the current organization). Maps to HTTP 404. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
