package com.company.incidentjmix.service;

/**
 * Business conflict → HTTP 409 at the REST boundary (ADR-005):
 * invalid status transition, transition to the same status, any change of a
 * Closed incident (PATCH, status, comments), reopening.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
