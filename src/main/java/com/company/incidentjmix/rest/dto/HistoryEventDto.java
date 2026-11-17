package com.company.incidentjmix.rest.dto;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.time.OffsetDateTime;

/**
 * History event representation for the REST contract (ADR-002).
 * payload is a JSON object deserialized from the stored JSON string.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record HistoryEventDto(
        Integer id,
        String incidentId,
        Integer actorId,
        String type,
        Object payload,
        OffsetDateTime createdAt) {
}
