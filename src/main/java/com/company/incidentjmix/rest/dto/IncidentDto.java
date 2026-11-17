package com.company.incidentjmix.rest.dto;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.time.OffsetDateTime;

/**
 * Incident representation for the REST contract (ADR-002, TASK.md §3).
 * snake_case, enum ids as strings, UTC ISO-8601 timestamps, assignee by id only.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record IncidentDto(
        String id,
        String title,
        String description,
        String category,
        String priority,
        String status,
        Integer assigneeId,
        String resolution,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
