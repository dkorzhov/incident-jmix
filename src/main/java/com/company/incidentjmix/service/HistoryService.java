package com.company.incidentjmix.service;

import com.company.incidentjmix.entity.AppUser;
import com.company.incidentjmix.entity.HistoryEvent;
import com.company.incidentjmix.entity.HistoryEventType;
import com.company.incidentjmix.entity.Incident;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import io.jmix.core.DataManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * Automatic history recording (ADR-005, FR-11). Called ONLY by domain
 * services inside their mutating transaction, so data change and history
 * commit or roll back together (AC-HIS-1). There is no public API to modify
 * or delete history events (AC-HIS-4).
 */
@Service
public class HistoryService {

    private final DataManager dataManager;
    private final ObjectMapper objectMapper;

    public HistoryService(DataManager dataManager, ObjectMapper objectMapper) {
        this.dataManager = dataManager;
        this.objectMapper = objectMapper;
    }

    /**
     * Records one event with a before/after payload serialized to a JSON
     * string. actor and createdAt are always server-filled.
     */
    @Transactional
    public void record(Incident incident, AppUser actor, HistoryEventType type, Map<String, Object> payload) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JacksonException e) {
            throw new IllegalStateException("Не удалось сериализовать payload истории", e);
        }
        HistoryEvent event = dataManager.create(HistoryEvent.class);
        event.setIncident(incident);
        event.setActor(actor);
        event.setType(type);
        event.setPayload(json);
        event.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS));
        dataManager.saveWithoutReload(event);
    }
}
