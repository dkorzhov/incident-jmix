package com.company.incidentjmix.service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Dashboard read model (ADR-002/005). Counts and aggregates are computed by
 * the database; recent_activity payload stays a JSON string — the REST
 * mapper (DMI-17) deserializes it into an object.
 *
 * @param counts         open/new/in_progress/critical/resolved_today
 * @param byPriority     open incidents per priority, fixed order
 *                       Critical, High, Medium, Low, non-zero entries only
 * @param recentActivity last 10 history events, createdAt desc, id desc
 * @param dynamics       last 7 UTC days including today, ascending, zero-days present
 */
public record DashboardData(Counts counts,
                            List<PriorityCount> byPriority,
                            List<ActivityItem> recentActivity,
                            List<DayDynamics> dynamics) {

    public record Counts(long open, long newCount, long inProgress, long critical, long resolvedToday) {
    }

    public record PriorityCount(String priority, long count) {
    }

    public record ActivityItem(String incidentId,
                               String incidentTitle,
                               Integer actorId,
                               String actorName,
                               String type,
                               String payload,
                               OffsetDateTime createdAt) {
    }

    public record DayDynamics(LocalDate date, long created, long resolved) {
    }
}
