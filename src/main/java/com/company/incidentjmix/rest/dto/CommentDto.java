package com.company.incidentjmix.rest.dto;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.time.OffsetDateTime;

/** Comment representation for the REST contract (ADR-002). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CommentDto(
        Integer id,
        String incidentId,
        Integer authorId,
        String text,
        OffsetDateTime createdAt) {
}
