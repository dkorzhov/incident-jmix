package com.company.incidentjmix.rest;

import com.company.incidentjmix.entity.AppUser;
import com.company.incidentjmix.entity.Comment;
import com.company.incidentjmix.entity.HistoryEvent;
import com.company.incidentjmix.entity.Incident;
import com.company.incidentjmix.rest.dto.CommentDto;
import com.company.incidentjmix.rest.dto.DashboardDto;
import com.company.incidentjmix.rest.dto.HistoryEventDto;
import com.company.incidentjmix.rest.dto.IncidentDetailsDto;
import com.company.incidentjmix.rest.dto.IncidentDto;
import com.company.incidentjmix.rest.dto.UserDto;
import com.company.incidentjmix.service.DashboardData;
import com.company.incidentjmix.service.IncidentDetails;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * Entity → REST DTO mapping (ADR-002). Pure transport shaping: enum ids as
 * strings, references as ids, stored history payload JSON strings exposed as
 * JSON objects. No business logic lives here.
 */
@Component
public class RestDtoMapper {

    private static final TypeReference<Map<String, Object>> PAYLOAD_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    public RestDtoMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public IncidentDto toIncidentDto(Incident incident) {
        return new IncidentDto(
                incident.getId(),
                incident.getTitle(),
                incident.getDescription(),
                incident.getCategory().getId(),
                incident.getPriority().getId(),
                incident.getStatus().getId(),
                incident.getAssignee() != null ? incident.getAssignee().getId() : null,
                incident.getResolution(),
                utc(incident.getCreatedAt()),
                utc(incident.getUpdatedAt()));
    }

    public IncidentDetailsDto toIncidentDetailsDto(IncidentDetails details) {
        Incident incident = details.incident();
        return new IncidentDetailsDto(
                incident.getId(),
                incident.getTitle(),
                incident.getDescription(),
                incident.getCategory().getId(),
                incident.getPriority().getId(),
                incident.getStatus().getId(),
                incident.getAssignee() != null ? incident.getAssignee().getId() : null,
                incident.getResolution(),
                utc(incident.getCreatedAt()),
                utc(incident.getUpdatedAt()),
                details.comments().stream().map(this::toCommentDto).toList(),
                details.history().stream().map(this::toHistoryEventDto).toList());
    }

    public CommentDto toCommentDto(Comment comment) {
        return new CommentDto(
                comment.getId(),
                comment.getIncident().getId(),
                comment.getAuthor().getId(),
                comment.getText(),
                utc(comment.getCreatedAt()));
    }

    public HistoryEventDto toHistoryEventDto(HistoryEvent event) {
        return new HistoryEventDto(
                event.getId(),
                event.getIncident().getId(),
                event.getActor().getId(),
                event.getType().getId(),
                parsePayload(event.getPayload()),
                utc(event.getCreatedAt()));
    }

    public UserDto toUserDto(AppUser user) {
        return new UserDto(user.getId(), user.getName(), user.getRole().getId());
    }

    public DashboardDto toDashboardDto(DashboardData data) {
        DashboardData.Counts counts = data.counts();
        return new DashboardDto(
                new DashboardDto.CountsDto(
                        counts.open(),
                        counts.newCount(),
                        counts.inProgress(),
                        counts.critical(),
                        counts.resolvedToday()),
                data.byPriority().stream()
                        .map(p -> new DashboardDto.PriorityCountDto(p.priority(), p.count()))
                        .toList(),
                data.recentActivity().stream()
                        .map(a -> new DashboardDto.ActivityItemDto(
                                a.incidentId(),
                                a.incidentTitle(),
                                a.actorId(),
                                a.actorName(),
                                a.type(),
                                parsePayload(a.payload()),
                                utc(a.createdAt())))
                        .toList(),
                data.dynamics().stream()
                        .map(d -> new DashboardDto.DayDynamicsDto(d.date(), d.created(), d.resolved()))
                        .toList());
    }

    /** Contract requires UTC ISO-8601; DB reads may carry the JVM zone offset. */
    private static OffsetDateTime utc(OffsetDateTime value) {
        return value != null ? value.withOffsetSameInstant(ZoneOffset.UTC) : null;
    }

    private Object parsePayload(String payload) {
        if (payload == null) {
            return null;
        }
        return objectMapper.readValue(payload, PAYLOAD_TYPE);
    }
}
