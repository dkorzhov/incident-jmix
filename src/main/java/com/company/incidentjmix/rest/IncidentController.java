package com.company.incidentjmix.rest;

import com.company.incidentjmix.entity.AppUser;
import com.company.incidentjmix.entity.Incident;
import com.company.incidentjmix.entity.IncidentPriority;
import com.company.incidentjmix.entity.IncidentStatus;
import com.company.incidentjmix.rest.dto.CommentDto;
import com.company.incidentjmix.rest.dto.CommentRequest;
import com.company.incidentjmix.rest.dto.CreateIncidentRequest;
import com.company.incidentjmix.rest.dto.IncidentDetailsDto;
import com.company.incidentjmix.rest.dto.IncidentDto;
import com.company.incidentjmix.rest.dto.StatusChangeRequest;
import com.company.incidentjmix.service.CommentService;
import com.company.incidentjmix.service.CreateCommand;
import com.company.incidentjmix.service.IncidentService;
import com.company.incidentjmix.service.PatchCommand;
import com.company.incidentjmix.service.ValidationException;
import io.jmix.core.security.SystemAuthenticator;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.function.Function;

/**
 * /api/incidents endpoints (TASK.md §3, ADR-002). Thin transport layer:
 * parse → domain service → DTO. All business rules and history recording
 * live in the DMI-16 services; every call runs with system authentication
 * while the domain actor comes from the X-User-Id header.
 * <p>
 * There is deliberately no DELETE mapping: deletion is out of MVP scope and
 * Spring answers 405 for DELETE on these paths.
 */
@RestController
@RequestMapping("/api/incidents")
public class IncidentController {

    private final IncidentService incidentService;
    private final CommentService commentService;
    private final SystemAuthenticator systemAuthenticator;
    private final RestDtoMapper mapper;

    public IncidentController(IncidentService incidentService,
                              CommentService commentService,
                              SystemAuthenticator systemAuthenticator,
                              RestDtoMapper mapper) {
        this.incidentService = incidentService;
        this.commentService = commentService;
        this.systemAuthenticator = systemAuthenticator;
        this.mapper = mapper;
    }

    @GetMapping
    public List<IncidentDto> list(@RequestParam(required = false) String q,
                                  @RequestParam(required = false) String status,
                                  @RequestParam(required = false) String priority,
                                  @CurrentAppUser(optional = true) AppUser actor) {
        // actor is resolved only to validate a present X-User-Id header;
        // reads do not need a domain actor.
        IncidentStatus statusFilter = parseFilter(status, "status", IncidentStatus::fromId);
        IncidentPriority priorityFilter = parseFilter(priority, "priority", IncidentPriority::fromId);
        List<Incident> incidents = systemAuthenticator.withSystem(
                () -> incidentService.list(q, statusFilter, priorityFilter));
        return incidents.stream().map(mapper::toIncidentDto).toList();
    }

    @PostMapping
    public ResponseEntity<IncidentDto> create(@RequestBody CreateIncidentRequest request,
                                              @CurrentAppUser AppUser actor) {
        Incident created = systemAuthenticator.withSystem(() -> incidentService.create(actor,
                new CreateCommand(request.title(), request.description(),
                        request.category(), request.priority(), request.assigneeId())));
        return ResponseEntity.status(HttpStatus.CREATED).body(mapper.toIncidentDto(created));
    }

    @GetMapping("/{id}")
    public IncidentDetailsDto get(@PathVariable String id,
                                  @CurrentAppUser(optional = true) AppUser actor) {
        return mapper.toIncidentDetailsDto(systemAuthenticator.withSystem(() -> incidentService.get(id)));
    }

    @PatchMapping("/{id}")
    public IncidentDto patch(@PathVariable String id,
                             @RequestBody JsonNode body,
                             @CurrentAppUser AppUser actor) {
        PatchCommand command = PatchRequestMapper.toPatchCommand(body);
        Incident updated = systemAuthenticator.withSystem(() -> incidentService.update(actor, id, command));
        return mapper.toIncidentDto(updated);
    }

    @PatchMapping("/{id}/status")
    public IncidentDto changeStatus(@PathVariable String id,
                                    @RequestBody StatusChangeRequest request,
                                    @CurrentAppUser AppUser actor) {
        // unknown status id → null → the service rejects it with 400
        IncidentStatus target = IncidentStatus.fromId(request.status());
        Incident updated = systemAuthenticator.withSystem(
                () -> incidentService.changeStatus(actor, id, target, request.resolution()));
        return mapper.toIncidentDto(updated);
    }

    @PostMapping("/{id}/comments")
    public ResponseEntity<CommentDto> addComment(@PathVariable String id,
                                                 @RequestBody CommentRequest request,
                                                 @CurrentAppUser AppUser actor) {
        var comment = systemAuthenticator.withSystem(() -> commentService.add(actor, id, request.text()));
        return ResponseEntity.status(HttpStatus.CREATED).body(mapper.toCommentDto(comment));
    }

    private static <E> E parseFilter(String value, String paramName, Function<String, E> fromId) {
        if (value == null) {
            return null;
        }
        E parsed = fromId.apply(value);
        if (parsed == null) {
            throw new ValidationException(paramName, "неизвестное значение: " + value);
        }
        return parsed;
    }
}
