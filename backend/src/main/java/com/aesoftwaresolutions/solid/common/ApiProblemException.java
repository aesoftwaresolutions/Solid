package com.aesoftwaresolutions.solid.common;

/** An error with an explicit HTTP status and machine-readable code (e.g. 401 INVALID_CREDENTIALS). */
public class ApiProblemException extends RuntimeException {

    private final int status;
    private final String code;

    public ApiProblemException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
