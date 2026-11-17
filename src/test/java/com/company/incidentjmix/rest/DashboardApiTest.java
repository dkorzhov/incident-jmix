package com.company.incidentjmix.rest;

import com.company.incidentjmix.entity.HistoryEventType;
import com.company.incidentjmix.entity.Incident;
import com.company.incidentjmix.entity.IncidentPriority;
import com.company.incidentjmix.entity.IncidentStatus;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/dashboard (TASK.md §3, ADR-002; AC-DASH-1…7). Counts must converge
 * with direct DB aggregates; the counts key for new incidents is exactly
 * "new" (coordinator requirement for DMI-17).
 */
class DashboardApiTest extends AbstractRestApiTest {

    @Test
    void dashboardCountsConvergeWithDatabaseAndUseNewKey() throws Exception {
        cleanup.purgeAllIncidents();
        createIncident("Дашборд новый критичный", "Network", "Critical", null);
        createIncident("Дашборд новый низкий", "Other", "Low", null);
        createInProgress("Дашборд в работе критичный", "Mail", "Critical");
        createResolved("Дашборд решённый сегодня", "Portal", "Medium", "Решено сегодня");
        createClosed("Дашборд закрытый критичный", "Database", "Critical", "Решено и закрыто");

        MvcResult result = getApiNoHeader("/api/dashboard")
                .andExpect(status().isOk())
                // mandatory key is "new", not new_count / newCount
                .andExpect(jsonPath("$.counts.new").value(2))
                .andExpect(jsonPath("$.counts.new_count").doesNotExist())
                .andExpect(jsonPath("$.counts.newCount").doesNotExist())
                .andExpect(jsonPath("$.counts.open").value(4))
                .andExpect(jsonPath("$.counts.in_progress").value(1))
                .andExpect(jsonPath("$.counts.critical").value(2))
                .andExpect(jsonPath("$.counts.resolved_today").value(2))
                .andReturn();

        // convergence with direct DB aggregates, not with the service itself
        assertThat(countByStatus(IncidentStatus.NEW)).isEqualTo(2);
        assertThat(countByStatus(IncidentStatus.IN_PROGRESS)).isEqualTo(1);
        assertThat(openCount()).isEqualTo(4);
        assertThat(criticalOpenCount()).isEqualTo(2);
        assertThat(resolvedTodayCount()).isEqualTo(2);

        JsonNode counts = bodyJson(result).get("counts");
        assertThat(counts.get("open").asLong()).isEqualTo(openCount());
        assertThat(counts.get("new").asLong()).isEqualTo(countByStatus(IncidentStatus.NEW));
        assertThat(counts.get("in_progress").asLong()).isEqualTo(countByStatus(IncidentStatus.IN_PROGRESS));
        assertThat(counts.get("critical").asLong()).isEqualTo(criticalOpenCount());
        assertThat(counts.get("resolved_today").asLong()).isEqualTo(resolvedTodayCount());

        // by_priority: open incidents only, fixed order, non-zero entries, sum = open
        JsonNode byPriority = bodyJson(result).get("by_priority");
        List<String> priorities = new ArrayList<>();
        long sum = 0;
        for (JsonNode node : byPriority) {
            priorities.add(node.get("priority").asString());
            sum += node.get("count").asLong();
        }
        assertThat(priorities).containsExactly("Critical", "Medium", "Low");
        assertThat(sum).isEqualTo(openCount());
        assertThat(byPriority.get(0).get("count").asLong()).isEqualTo(2);
        assertThat(byPriority.get(1).get("count").asLong()).isEqualTo(1);
        assertThat(byPriority.get(2).get("count").asLong()).isEqualTo(1);
    }

    @Test
    void recentActivityIsTenLatestEventsDescWithNamesAndPayload() throws Exception {
        cleanup.purgeAllIncidents();
        Incident incident = createIncident("Лента активности", "Mail", "High", null);
        for (int i = 1; i <= 12; i++) {
            commentService.add(actor, incident.getId(), "Комментарий " + i);
        }

        JsonNode activity = bodyJson(getApiNoHeader("/api/dashboard").andReturn())
                .get("recent_activity");
        assertThat(activity).hasSize(10);

        List<OffsetDateTime> timestamps = new ArrayList<>();
        for (JsonNode item : activity) {
            assertThat(item.get("incident_id").asString()).isEqualTo(incident.getId());
            assertThat(item.get("incident_title").asString()).isEqualTo("Лента активности");
            assertThat(item.get("actor_id").asInt()).isEqualTo(1);
            assertThat(item.get("actor_name").asString()).isEqualTo(actor.getName());
            assertThat(item.get("type").asString()).isEqualTo("commented");
            assertThat(item.get("payload").isObject()).isTrue();
            assertThat(item.get("payload").get("after").get("comment_id").isIntegralNumber()).isTrue();
            assertThat(item.has("created_at")).isTrue();
            timestamps.add(OffsetDateTime.parse(item.get("created_at").asString()));
        }
        // latest first (equal microsecond timestamps are allowed)
        assertThat(timestamps).isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    @Test
    void dynamicsCoverSevenUtcDaysAscendingIncludingZeroDays() throws Exception {
        cleanup.purgeAllIncidents();

        JsonNode empty = bodyJson(getApiNoHeader("/api/dashboard").andReturn()).get("dynamics");
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        assertThat(empty).hasSize(7);
        for (int i = 0; i < 7; i++) {
            assertThat(empty.get(i).get("date").asString()).isEqualTo(today.minusDays(6 - i).toString());
            assertThat(empty.get(i).get("created").asLong()).isZero();
            assertThat(empty.get(i).get("resolved").asLong()).isZero();
        }

        createIncident("Динамика создание", "Network", "Low", null);
        createResolved("Динамика решение", "Mail", "High", "Готово");

        JsonNode dynamics = bodyJson(getApiNoHeader("/api/dashboard").andReturn()).get("dynamics");
        assertThat(dynamics).hasSize(7);
        JsonNode todayNode = dynamics.get(6);
        assertThat(todayNode.get("date").asString()).isEqualTo(today.toString());
        assertThat(todayNode.get("created").asLong()).isEqualTo(2);
        assertThat(todayNode.get("resolved").asLong()).isEqualTo(1);
    }

    @Test
    void emptyDatabaseProducesZeroDashboard() throws Exception {
        cleanup.purgeAllIncidents();

        getApiNoHeader("/api/dashboard")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.open").value(0))
                .andExpect(jsonPath("$.counts.new").value(0))
                .andExpect(jsonPath("$.counts.in_progress").value(0))
                .andExpect(jsonPath("$.counts.critical").value(0))
                .andExpect(jsonPath("$.counts.resolved_today").value(0))
                .andExpect(jsonPath("$.by_priority").isEmpty())
                .andExpect(jsonPath("$.recent_activity").isEmpty())
                .andExpect(jsonPath("$.dynamics.length()").value(7));
    }

    @Test
    void dashboardReadDoesNotWriteHistory() throws Exception {
        Incident incident = createIncident("Чтение без записи", "Network", "Low", null);
        long historyBefore = totalHistoryCount();

        getApiNoHeader("/api/dashboard").andExpect(status().isOk());
        getApi("/api/dashboard").andExpect(status().isOk());

        assertThat(totalHistoryCount()).isEqualTo(historyBefore);
        assertThat(historyCount(incident.getId())).isEqualTo(1);
    }

    @Test
    void dashboardWithUnknownHeaderReturns401() throws Exception {
        performApi(get("/api/dashboard").header("X-User-Id", "999"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").exists());
    }

    // ---------- direct DB aggregates ----------

    private long countByStatus(IncidentStatus status) {
        return dataManager.loadValue("select count(e) from inc_Incident e where e.status = :status", Long.class)
                .parameter("status", status)
                .one();
    }

    private long openCount() {
        return dataManager.loadValue(
                        "select count(e) from inc_Incident e where e.status <> :closed", Long.class)
                .parameter("closed", IncidentStatus.CLOSED)
                .one();
    }

    private long criticalOpenCount() {
        return dataManager.loadValue(
                        "select count(e) from inc_Incident e where e.priority = :critical and e.status <> :closed",
                        Long.class)
                .parameter("critical", IncidentPriority.CRITICAL)
                .parameter("closed", IncidentStatus.CLOSED)
                .one();
    }

    private long resolvedTodayCount() {
        OffsetDateTime todayStart = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
        return dataManager.loadValue(
                        "select count(e) from inc_HistoryEvent e where e.type = :type and e.createdAt >= :start",
                        Long.class)
                .parameter("type", HistoryEventType.RESOLVED)
                .parameter("start", todayStart)
                .one();
    }
}
