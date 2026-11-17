package com.company.incidentjmix.rest;

import com.company.incidentjmix.entity.Incident;
import com.company.incidentjmix.entity.IncidentStatus;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PATCH /api/incidents/{id}/status — the state automaton (TASK.md §2,
 * ADR-002; AC-STATUS-1…9). Also closes the PR #3 review finding: updated_at
 * must change after changeStatus (New→In Progress and Resolved→Closed).
 */
class StatusApiTest extends AbstractRestApiTest {

    @Test
    void fullLifecycleSucceedsWithCorrectHistory() throws Exception {
        Incident incident = createIncident("Полный жизненный цикл", "Mail", "High", null);
        String id = incident.getId();

        // New → In Progress: status_changed event
        patchApi("/api/incidents/" + id + "/status", "{\"status\": \"In Progress\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("In Progress"));

        // In Progress → Resolved: ONLY resolved event, resolution stored
        patchApi("/api/incidents/" + id + "/status", """
                {"status": "Resolved", "resolution": "Почтовый сервер перезапущен"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Resolved"))
                .andExpect(jsonPath("$.resolution").value("Почтовый сервер перезапущен"));

        // Resolved → Closed: status_changed event
        patchApi("/api/incidents/" + id + "/status", "{\"status\": \"Closed\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Closed"));

        JsonNode history = bodyJson(getApi("/api/incidents/" + id).andReturn()).get("history");
        assertThat(history).hasSize(4);
        assertThat(history.get(0).get("type").asString()).isEqualTo("status_changed");
        assertThat(history.get(0).get("payload").get("before").get("status").asString()).isEqualTo("Resolved");
        assertThat(history.get(0).get("payload").get("after").get("status").asString()).isEqualTo("Closed");
        assertThat(history.get(1).get("type").asString()).isEqualTo("resolved");
        assertThat(history.get(1).get("payload").get("before").get("status").asString()).isEqualTo("In Progress");
        assertThat(history.get(1).get("payload").get("before").get("resolution").isNull()).isTrue();
        assertThat(history.get(1).get("payload").get("after").get("status").asString()).isEqualTo("Resolved");
        assertThat(history.get(1).get("payload").get("after").get("resolution").asString())
                .isEqualTo("Почтовый сервер перезапущен");
        assertThat(history.get(2).get("type").asString()).isEqualTo("status_changed");
        assertThat(history.get(2).get("payload").get("before").get("status").asString()).isEqualTo("New");
        assertThat(history.get(2).get("payload").get("after").get("status").asString()).isEqualTo("In Progress");
        assertThat(history.get(3).get("type").asString()).isEqualTo("created");
        // every event actor = header user
        history.forEach(e -> assertThat(e.get("actor_id").asInt()).isEqualTo(1));
    }

    @Test
    void updatedAtChangesAfterStatusTransitions() throws Exception {
        // review finding on PR #3: changeStatus must bump updated_at
        Incident incident = createIncident("Метка времени статуса", "Network", "Low", null);
        String id = incident.getId();
        OffsetDateTime created = incident.getUpdatedAt();

        MvcResult take = patchApi("/api/incidents/" + id + "/status", "{\"status\": \"In Progress\"}")
                .andExpect(status().isOk())
                .andReturn();
        OffsetDateTime afterTake = OffsetDateTime.parse(bodyJson(take).get("updated_at").asString());
        assertThat(afterTake).isAfter(created);

        patchApi("/api/incidents/" + id + "/status",
                "{\"status\": \"Resolved\", \"resolution\": \"Готово\"}")
                .andExpect(status().isOk());

        Incident resolved = incidentService.get(id).incident();
        MvcResult close = patchApi("/api/incidents/" + id + "/status", "{\"status\": \"Closed\"}")
                .andExpect(status().isOk())
                .andReturn();
        OffsetDateTime afterClose = OffsetDateTime.parse(bodyJson(close).get("updated_at").asString());
        assertThat(afterClose).isAfter(resolved.getUpdatedAt());
    }

    @Test
    void invalidTransitionsReturn409AndWriteNothing() throws Exception {
        Incident newIncident = createIncident("Новый для матрицы", "Network", "Low", null);
        Incident inProgress = createInProgress("В работе для матрицы", "Mail", "High");
        Incident resolved = createResolved("Решён для матрицы", "Portal", "Medium", "Решение");
        Incident closed = createClosed("Закрыт для матрицы", "Other", "Low", "Решение");

        assertConflict(newIncident.getId(), "Closed", null);
        assertConflict(newIncident.getId(), "Resolved", "Попытка решить сразу");
        assertConflict(newIncident.getId(), "New", null);
        assertConflict(inProgress.getId(), "Closed", null);
        assertConflict(inProgress.getId(), "New", null);
        assertConflict(inProgress.getId(), "In Progress", null);
        assertConflict(resolved.getId(), "In Progress", null);
        assertConflict(resolved.getId(), "New", null);
        assertConflict(resolved.getId(), "Resolved", "Ещё раз");
        assertConflict(closed.getId(), "New", null);
        assertConflict(closed.getId(), "In Progress", null);
        assertConflict(closed.getId(), "Resolved", "Попытка");
        assertConflict(closed.getId(), "Closed", null);

        // statuses unchanged, no extra history events
        assertThat(incidentService.get(newIncident.getId()).incident().getStatus()).isEqualTo(IncidentStatus.NEW);
        assertThat(incidentService.get(inProgress.getId()).incident().getStatus()).isEqualTo(IncidentStatus.IN_PROGRESS);
        assertThat(incidentService.get(resolved.getId()).incident().getStatus()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(incidentService.get(closed.getId()).incident().getStatus()).isEqualTo(IncidentStatus.CLOSED);
        assertThat(historyCount(newIncident.getId())).isEqualTo(1);
        assertThat(historyCount(inProgress.getId())).isEqualTo(2);
        assertThat(historyCount(resolved.getId())).isEqualTo(3);
        assertThat(historyCount(closed.getId())).isEqualTo(4);
    }

    @Test
    void resolveWithoutResolutionReturns400() throws Exception {
        Incident incident = createInProgress("Без решения", "Mail", "Critical");
        String id = incident.getId();
        long historyBefore = historyCount(id);

        patchApi("/api/incidents/" + id + "/status", "{\"status\": \"Resolved\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("resolution")));
        patchApi("/api/incidents/" + id + "/status", "{\"status\": \"Resolved\", \"resolution\": null}")
                .andExpect(status().isBadRequest());
        patchApi("/api/incidents/" + id + "/status", "{\"status\": \"Resolved\", \"resolution\": \"   \"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("resolution")));

        assertThat(incidentService.get(id).incident().getStatus()).isEqualTo(IncidentStatus.IN_PROGRESS);
        assertThat(historyCount(id)).isEqualTo(historyBefore);
    }

    @Test
    void resolveWithOverlongResolutionReturns400() throws Exception {
        Incident incident = createInProgress("Длинное решение", "Mail", "High");
        String id = incident.getId();

        patchApi("/api/incidents/" + id + "/status",
                "{\"status\": \"Resolved\", \"resolution\": \"" + "x".repeat(2001) + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("resolution")));

        assertThat(incidentService.get(id).incident().getStatus()).isEqualTo(IncidentStatus.IN_PROGRESS);
    }

    @Test
    void unknownOrMissingStatusReturns400() throws Exception {
        Incident incident = createIncident("Неизвестный статус", "Network", "Low", null);
        String id = incident.getId();

        patchApi("/api/incidents/" + id + "/status", "{\"status\": \"Bogus\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("status")));
        patchApi("/api/incidents/" + id + "/status", "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("status")));
        patchApi("/api/incidents/" + id + "/status", "{\"status\": null}")
                .andExpect(status().isBadRequest());

        assertThat(incidentService.get(id).incident().getStatus()).isEqualTo(IncidentStatus.NEW);
        assertThat(historyCount(id)).isEqualTo(1);
    }

    @Test
    void resolutionIsIgnoredOnNonResolvedTransitions() throws Exception {
        Incident incident = createIncident("Решение игнорируется", "Network", "Low", null);
        String id = incident.getId();

        patchApi("/api/incidents/" + id + "/status",
                "{\"status\": \"In Progress\", \"resolution\": \"Не должно сохраниться\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("In Progress"))
                .andExpect(jsonPath("$.resolution").value(nullValue()));

        JsonNode history = bodyJson(getApi("/api/incidents/" + id).andReturn()).get("history");
        assertThat(history).hasSize(2);
        assertThat(history.get(0).get("type").asString()).isEqualTo("status_changed");
    }

    @Test
    void statusChangeUnknownIdReturns404AndMissingHeaderReturns401() throws Exception {
        patchApi("/api/incidents/INC-99999/status", "{\"status\": \"In Progress\"}")
                .andExpect(status().isNotFound());

        Incident incident = createIncident("Без пользователя", "Network", "Low", null);
        performApi(patch("/api/incidents/" + incident.getId() + "/status")
                        .contentType("application/json")
                        .content("{\"status\": \"In Progress\"}"))
                .andExpect(status().isUnauthorized());
        assertThat(incidentService.get(incident.getId()).incident().getStatus()).isEqualTo(IncidentStatus.NEW);
    }

    private void assertConflict(String id, String targetStatus, String resolution) throws Exception {
        String body = resolution == null
                ? "{\"status\": \"" + targetStatus + "\"}"
                : "{\"status\": \"" + targetStatus + "\", \"resolution\": \"" + resolution + "\"}";
        patchApi("/api/incidents/" + id + "/status", body)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").exists());
    }
}
