package com.aesoftwaresolutions.solid.money;

/** Thrown when an operation combines amounts in different currencies. */
public class CurrencyMismatchException extends RuntimeException {

    public CurrencyMismatchException(String left, String right) {
        super("Currency mismatch: " + left + " vs " + right);
    }
}
