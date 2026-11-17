package com.company.incidentjmix.rest.dto;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * GET /api/incidents/{id}: incident fields plus comments (created_at asc, id asc)
 * and history (created_at desc, id desc) — ADR-002.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record IncidentDetailsDto(
        String id,
        String title,
        String description,
        String category,
        String priority,
        String status,
        Integer assigneeId,
        String resolution,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        List<CommentDto> comments,
        List<HistoryEventDto> history) {
}
