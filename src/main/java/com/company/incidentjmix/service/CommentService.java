package com.company.incidentjmix.service;

import com.company.incidentjmix.entity.AppUser;
import com.company.incidentjmix.entity.Comment;
import com.company.incidentjmix.entity.HistoryEventType;
import com.company.incidentjmix.entity.Incident;
import com.company.incidentjmix.entity.IncidentStatus;
import io.jmix.core.DataManager;
import io.jmix.core.FetchPlan;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

/**
 * Comments on incidents (TASK.md §3, ADR-005). Author is always the domain
 * actor passed by the caller, never taken from the payload. Adding a comment
 * does NOT touch incident.updatedAt. Closed incidents reject comments (409).
 */
@Service
public class CommentService {

    private final DataManager dataManager;
    private final HistoryService historyService;

    public CommentService(DataManager dataManager, HistoryService historyService) {
        this.dataManager = dataManager;
        this.historyService = historyService;
    }

    @Transactional
    public Comment add(AppUser actor, String incidentId, String text) {
        Incident incident = dataManager.load(Incident.class).id(incidentId)
                .fetchPlan(FetchPlan.BASE)
                .optional()
                .orElseThrow(() -> new NotFoundException("Инцидент не найден: " + incidentId));
        if (incident.getStatus() == IncidentStatus.CLOSED) {
            throw new ConflictException(IncidentService.CLOSED_MESSAGE);
        }
        if (text == null || text.trim().isEmpty()) {
            throw new ValidationException("text", "обязательное поле");
        }
        String trimmed = text.trim();
        if (trimmed.length() > 2000) {
            throw new ValidationException("text", "длина 1–2000 символов");
        }

        Comment comment = dataManager.create(Comment.class);
        comment.setIncident(incident);
        comment.setAuthor(actor);
        comment.setText(trimmed);
        comment.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS));
        Comment saved = dataManager.save(comment);

        Map<String, Object> after = new HashMap<>();
        after.put("comment_id", saved.getId());
        after.put("text", saved.getText());
        historyService.record(incident, actor, HistoryEventType.COMMENTED, Map.of("after", after));
        return saved;
    }
}
