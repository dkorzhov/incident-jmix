package com.company.incidentjmix.service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Field validation failure → HTTP 400 at the REST boundary (ADR-005).
 * Carries field → reason pairs; the message lists all of them, e.g.
 * "title: длина 5–120 символов; priority: неизвестное значение".
 * RuntimeException so the surrounding transaction rolls back.
 */
public class ValidationException extends RuntimeException {

    private final Map<String, String> fieldErrors;

    public ValidationException(String field, String reason) {
        this(Map.of(field, reason));
    }

    public ValidationException(Map<String, String> fieldErrors) {
        super(format(fieldErrors));
        this.fieldErrors = Collections.unmodifiableMap(new LinkedHashMap<>(fieldErrors));
    }

    public Map<String, String> getFieldErrors() {
        return fieldErrors;
    }

    private static String format(Map<String, String> fieldErrors) {
        return fieldErrors.entrySet().stream()
                .map(e -> e.getKey() + ": " + e.getValue())
                .collect(Collectors.joining("; "));
    }
}
