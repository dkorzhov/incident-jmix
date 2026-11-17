package com.company.incidentjmix.service;

import com.company.incidentjmix.entity.HistoryEvent;
import com.company.incidentjmix.entity.HistoryEventType;
import com.company.incidentjmix.entity.IncidentPriority;
import com.company.incidentjmix.entity.IncidentStatus;
import io.jmix.core.DataManager;
import io.jmix.core.FetchPlan;
import io.jmix.core.entity.KeyValueEntity;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dashboard metrics computed from the database (TASK.md §3, ADR-005).
 * Read-only: never writes history. All day boundaries are UTC.
 * open = status != Closed; critical = Critical AND not Closed;
 * resolved_today = resolved history events created today UTC (a
 * resolved-then-closed incident still counts).
 */
@Service
public class DashboardService {

    private final DataManager dataManager;

    public DashboardService(DataManager dataManager) {
        this.dataManager = dataManager;
    }

    public DashboardData getDashboard() {
        OffsetDateTime todayStart = LocalDate.now(ZoneOffset.UTC).atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime tomorrowStart = todayStart.plusDays(1);
        OffsetDateTime windowStart = todayStart.minusDays(6);

        List<KeyValueEntity> rows = dataManager.loadValues(
                        "select e.status, e.priority, count(e) from inc_Incident e group by e.status, e.priority")
                .properties("status", "priority", "cnt")
                .list();
        long open = 0;
        long newCount = 0;
        long inProgress = 0;
        long critical = 0;
        Map<String, Long> openByPriority = new HashMap<>();
        for (KeyValueEntity row : rows) {
            String status = row.getValue("status");
            String priority = row.getValue("priority");
            long cnt = ((Number) row.getValue("cnt")).longValue();
            boolean isOpen = !IncidentStatus.CLOSED.getId().equals(status);
            if (isOpen) {
                open += cnt;
                openByPriority.merge(priority, cnt, Long::sum);
            }
            if (IncidentStatus.NEW.getId().equals(status)) {
                newCount += cnt;
            }
            if (IncidentStatus.IN_PROGRESS.getId().equals(status)) {
                inProgress += cnt;
            }
            if (isOpen && IncidentPriority.CRITICAL.getId().equals(priority)) {
                critical += cnt;
            }
        }

        long resolvedToday = dataManager.loadValue(
                        "select count(e) from inc_HistoryEvent e where e.type = :type" +
                                " and e.createdAt >= :start and e.createdAt < :end", Long.class)
                .parameter("type", HistoryEventType.RESOLVED.getId())
                .parameter("start", todayStart)
                .parameter("end", tomorrowStart)
                .one();

        List<DashboardData.PriorityCount> byPriority = new ArrayList<>();
        for (IncidentPriority priority : IncidentPriority.values()) {
            long cnt = openByPriority.getOrDefault(priority.getId(), 0L);
            if (cnt > 0) {
                byPriority.add(new DashboardData.PriorityCount(priority.getId(), cnt));
            }
        }

        List<HistoryEvent> events = dataManager.load(HistoryEvent.class)
                .query("select e from inc_HistoryEvent e order by e.createdAt desc, e.id desc")
                .maxResults(10)
                .fetchPlan(fp -> fp.addFetchPlan(FetchPlan.BASE)
                        .add("incident", FetchPlan.BASE)
                        .add("actor", FetchPlan.BASE))
                .list();
        List<DashboardData.ActivityItem> recentActivity = events.stream()
                .map(e -> new DashboardData.ActivityItem(
                        e.getIncident().getId(),
                        e.getIncident().getTitle(),
                        e.getActor().getId(),
                        e.getActor().getName(),
                        e.getType().getId(),
                        e.getPayload(),
                        e.getCreatedAt()))
                .toList();

        Map<LocalDate, long[]> buckets = new LinkedHashMap<>();
        for (int i = 6; i >= 0; i--) {
            buckets.put(todayStart.toLocalDate().minusDays(i), new long[2]);
        }
        dataManager.loadValues("select e.createdAt from inc_Incident e" +
                        " where e.createdAt >= :start and e.createdAt < :end")
                .property("createdAt")
                .parameter("start", windowStart)
                .parameter("end", tomorrowStart)
                .list()
                .forEach(kv -> countInBucket(buckets, kv.getValue("createdAt"), 0));
        dataManager.loadValues("select e.createdAt from inc_HistoryEvent e where e.type = :type" +
                        " and e.createdAt >= :start and e.createdAt < :end")
                .property("createdAt")
                .parameter("type", HistoryEventType.RESOLVED.getId())
                .parameter("start", windowStart)
                .parameter("end", tomorrowStart)
                .list()
                .forEach(kv -> countInBucket(buckets, kv.getValue("createdAt"), 1));
        List<DashboardData.DayDynamics> dynamics = buckets.entrySet().stream()
                .map(e -> new DashboardData.DayDynamics(e.getKey(), e.getValue()[0], e.getValue()[1]))
                .toList();

        return new DashboardData(
                new DashboardData.Counts(open, newCount, inProgress, critical, resolvedToday),
                byPriority, recentActivity, dynamics);
    }

    private static void countInBucket(Map<LocalDate, long[]> buckets, OffsetDateTime timestamp, int index) {
        if (timestamp == null) {
            return;
        }
        LocalDate day = timestamp.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
        long[] bucket = buckets.get(day);
        if (bucket != null) {
            bucket[index]++;
        }
    }
}
