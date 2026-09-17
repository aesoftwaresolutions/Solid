package com.aesoftwaresolutions.solid.common;

/** A request was valid in shape but breaks a business rule. Maps to HTTP 409. */
public class BusinessRuleException extends RuntimeException {

    private final String code;

    public BusinessRuleException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
