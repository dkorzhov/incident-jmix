package com.company.incidentjmix.service;

import org.jspecify.annotations.Nullable;

/**
 * Command for IncidentService.create (ADR-005). category/priority arrive as
 * raw string ids so the service is the single validation point (NFR-7):
 * unknown values become ValidationException (400), not 409.
 */
public record CreateCommand(
        @Nullable String title,
        @Nullable String description,
        @Nullable String category,
        @Nullable String priority,
        @Nullable Integer assigneeId) {
}
