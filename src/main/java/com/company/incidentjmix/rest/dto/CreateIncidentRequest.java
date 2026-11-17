package com.company.incidentjmix.rest.dto;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * POST /api/incidents body (ADR-002). category/priority stay raw string ids:
 * the domain service is the single validation point. Unknown fields
 * (including a client-supplied id) are ignored by Jackson.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CreateIncidentRequest(
        String title,
        String description,
        String category,
        String priority,
        Integer assigneeId) {
}
