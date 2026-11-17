package com.company.incidentjmix.rest.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * GET /api/dashboard (ADR-002). The counts key for new incidents is exactly
 * "new" (a Java reserved word, hence the explicit @JsonProperty).
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DashboardDto(
        CountsDto counts,
        List<PriorityCountDto> byPriority,
        List<ActivityItemDto> recentActivity,
        List<DayDynamicsDto> dynamics) {

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record CountsDto(
            long open,
            @JsonProperty("new") long newCount,
            long inProgress,
            long critical,
            long resolvedToday) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PriorityCountDto(String priority, long count) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ActivityItemDto(
            String incidentId,
            String incidentTitle,
            Integer actorId,
            String actorName,
            String type,
            Object payload,
            OffsetDateTime createdAt) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record DayDynamicsDto(LocalDate date, long created, long resolved) {
    }
}
