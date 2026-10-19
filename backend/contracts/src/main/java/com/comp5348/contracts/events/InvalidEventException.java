package com.comp5348.contracts.events;

/**
 * The message itself is broken (unparseable, missing fields, wrong types).
 * Retrying can never fix it, so consumers send it to the DLT (rule 6).
 */
public class InvalidEventException extends Exception {

    public InvalidEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
