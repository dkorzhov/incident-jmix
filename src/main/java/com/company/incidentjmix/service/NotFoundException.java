package com.company.incidentjmix.service;

/**
 * Requested incident does not exist → HTTP 404 at the REST boundary (ADR-005).
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
