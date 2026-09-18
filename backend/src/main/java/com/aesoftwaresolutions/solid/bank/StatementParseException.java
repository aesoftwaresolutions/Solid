package com.aesoftwaresolutions.solid.bank;

/** A statement file couldn't be read. Always says where, so the user can fix the file. */
class StatementParseException extends IllegalArgumentException {

    StatementParseException(int line, String message) {
        super(line > 0 ? "Line " + line + ": " + message : message);
    }
}
