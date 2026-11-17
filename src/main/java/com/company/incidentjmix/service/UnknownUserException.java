package com.company.incidentjmix.service;

/**
 * X-User-Id is absent (when required), non-numeric or unknown → HTTP 401
 * at the REST boundary (ADR-003/005).
 */
public class UnknownUserException extends RuntimeException {

    public UnknownUserException(String message) {
        super(message);
    }
}
