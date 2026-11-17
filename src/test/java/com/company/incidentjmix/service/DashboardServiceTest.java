package com.company.incidentjmix.service;

import com.company.incidentjmix.entity.AppUser;
import com.company.incidentjmix.entity.HistoryEvent;
import com.company.incidentjmix.entity.HistoryEventType;
import com.company.incidentjmix.entity.Incident;
import com.company.incidentjmix.entity.IncidentStatus;
import com.company.incidentjmix.test_support.AuthenticatedAsAdmin;
import com.company.incidentjmix.test_support.DomainCleanup;
import io.jmix.core.DataManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dashboard convergence with the database (AC-DASH-2). Assertions use
 * deltas against a baseline because the HSQLDB file store is shared.
 */
@SpringBootTest
@ExtendWith(AuthenticatedAsAdmin.class)
@ActiveProfiles("test")
class DashboardServiceTest {

    @Autowired
    private DashboardService dashboardService;
    @Autowired
    private IncidentService incidentService;
    @Autowired
    private CommentService commentService;
    @Autowired
    private AppUserService appUserService;
    @Autowired
    private DataManager dataManager;
    @Autowired
    private ObjectMapper objectMapper;

    private DomainCleanup cleanup;
    private AppUser actor;

    @BeforeEach
    void setUp() {
        cleanup = new DomainCleanup(dataManager);
        actor = appUserService.getById(1);
    }

    @AfterEach
    void tearDown() {
        cleanup.cleanup();
    }

    private Incident create(String title, String priority) {
        Incident incident = incidentService.create(actor,
                new CreateCommand(title, null, "Other", priority, null));
        cleanup.track(incident);
        return incident;
    }

    private void resolve(Incident incident) {
        incidentService.changeStatus(actor, incident.getId(), IncidentStatus.IN_PROGRESS, null);
        incidentService.changeStatus(actor, incident.getId(), IncidentStatus.RESOLVED, "Решено");
    }

    private Map<String, Long> priorityMap(DashboardData data) {
        return data.byPriority().stream()
                .collect(Collectors.toMap(DashboardData.PriorityCount::priority, DashboardData.PriorityCount::count));
    }

    @Test
    void countsAndAggregatesConvergeWithDatabase() {
        DashboardData before = dashboardService.getDashboard();
        Map<String, Long> byPriorityBefore = priorityMap(before);

        Incident a = create("Дашборд новый критический", "Critical");
        Incident b = create("Дашборд в работе", "High");
        incidentService.changeStatus(actor, b.getId(), IncidentStatus.IN_PROGRESS, null);
        Incident c = create("Дашборд решённый сегодня", "Medium");
        resolve(c);
        Incident d = create("Дашборд закрытый сегодня", "Low");
        resolve(d);
        incidentService.changeStatus(actor, d.getId(), IncidentStatus.CLOSED, null);

        DashboardData after = dashboardService.getDashboard();

        assertThat(after.counts().open()).isEqualTo(before.counts().open() + 3);
        assertThat(after.counts().newCount()).isEqualTo(before.counts().newCount() + 1);
        assertThat(after.counts().inProgress()).isEqualTo(before.counts().inProgress() + 1);
        assertThat(after.counts().critical()).isEqualTo(before.counts().critical() + 1);
        // both c and d produced a 'resolved' event today; d still counts after Closed
        assertThat(after.counts().resolvedToday()).isEqualTo(before.counts().resolvedToday() + 2);

        Map<String, Long> byPriorityAfter = priorityMap(after);
        assertThat(byPriorityAfter.getOrDefault("Critical", 0L))
                .isEqualTo(byPriorityBefore.getOrDefault("Critical", 0L) + 1);
        assertThat(byPriorityAfter.getOrDefault("High", 0L))
                .isEqualTo(byPriorityBefore.getOrDefault("High", 0L) + 1);
        assertThat(byPriorityAfter.getOrDefault("Medium", 0L))
                .isEqualTo(byPriorityBefore.getOrDefault("Medium", 0L) + 1);
        assertThat(byPriorityAfter.getOrDefault("Low", 0L))
                .isEqualTo(byPriorityBefore.getOrDefault("Low", 0L)); // d is Closed, excluded
        assertThat(after.byPriority().stream().mapToLong(DashboardData.PriorityCount::count).sum())
                .isEqualTo(after.counts().open());
        // fixed order Critical, High, Medium, Low (non-zero entries only)
        List<String> order = List.of("Critical", "High", "Medium", "Low");
        assertThat(after.byPriority()).extracting(DashboardData.PriorityCount::priority)
                .isSortedAccordingTo((p1, p2) -> Integer.compare(order.indexOf(p1), order.indexOf(p2)));

        // recent activity: latest events first, our closed event on top
        assertThat(after.recentActivity()).isNotEmpty();
        DashboardData.ActivityItem top = after.recentActivity().get(0);
        assertThat(top.incidentId()).isEqualTo(d.getId());
        assertThat(top.type()).isEqualTo("status_changed");
        assertThat(top.incidentTitle()).isEqualTo("Дашборд закрытый сегодня");
        assertThat(top.actorId()).isEqualTo(actor.getId());
        assertThat(top.actorName()).isEqualTo("Иванов И.И.");
        assertThat(objectMapper.readTree(top.payload()).isObject()).isTrue();
        assertThat(after.recentActivity()).extracting(DashboardData.ActivityItem::incidentId)
                .contains(a.getId(), b.getId(), c.getId(), d.getId());

        // dynamics: 7 UTC days ascending incl. today; today gained 4 created, 2 resolved
        assertThat(after.dynamics()).hasSize(7);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        assertThat(after.dynamics().get(6).date()).isEqualTo(today);
        for (int i = 0; i < 7; i++) {
            assertThat(after.dynamics().get(i).date()).isEqualTo(today.minusDays(6 - i));
        }
        DashboardData.DayDynamics todayBefore = before.dynamics().get(6);
        DashboardData.DayDynamics todayAfter = after.dynamics().get(6);
        assertThat(todayAfter.created()).isEqualTo(todayBefore.created() + 4);
        assertThat(todayAfter.resolved()).isEqualTo(todayBefore.resolved() + 2);
    }

    @Test
    void emptyDatabaseProducesZeroDashboard() {
        cleanup.purgeAllIncidents();

        DashboardData data = dashboardService.getDashboard();

        assertThat(data.counts().open()).isZero();
        assertThat(data.counts().newCount()).isZero();
        assertThat(data.counts().inProgress()).isZero();
        assertThat(data.counts().critical()).isZero();
        assertThat(data.counts().resolvedToday()).isZero();
        assertThat(data.byPriority()).isEmpty();
        assertThat(data.recentActivity()).isEmpty();
        assertThat(data.dynamics()).hasSize(7);
        assertThat(data.dynamics()).allSatisfy(day -> {
            assertThat(day.created()).isZero();
            assertThat(day.resolved()).isZero();
        });
    }

    @Test
    void recentActivityIsLimitedToTenLatestEvents() {
        Incident incident = create("Дашборд лента активности", "Low");
        for (int i = 1; i <= 12; i++) {
            commentService.add(actor, incident.getId(), "Комментарий номер " + i);
        }

        DashboardData data = dashboardService.getDashboard();

        assertThat(data.recentActivity()).hasSize(10);
        assertThat(data.recentActivity()).allSatisfy(item -> {
            assertThat(item.incidentId()).isEqualTo(incident.getId());
            assertThat(item.type()).isEqualTo("commented");
        });
        assertThat(objectMapper.readTree(data.recentActivity().get(0).payload())
                .get("after").get("text").asString()).isEqualTo("Комментарий номер 12");
        assertThat(data.recentActivity()).isSortedAccordingTo(
                (x, y) -> y.createdAt().compareTo(x.createdAt()));
    }

    @Test
    void resolvedYesterdayDoesNotCountIntoResolvedToday() {
        DashboardData before = dashboardService.getDashboard();

        Incident incident = create("Дашборд решённый вчера", "High");
        resolve(incident);

        // backdate the resolved event directly in the store (test-only setup;
        // the service API itself never mutates history)
        HistoryEvent resolvedEvent = dataManager.load(HistoryEvent.class)
                .query("select e from inc_HistoryEvent e where e.incident.id = :id and e.type = :type")
                .parameter("id", incident.getId())
                .parameter("type", HistoryEventType.RESOLVED.getId())
                .one();
        OffsetDateTime yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1)
                .atTime(12, 0).atOffset(ZoneOffset.UTC);
        resolvedEvent.setCreatedAt(yesterday);
        dataManager.saveWithoutReload(resolvedEvent);

        DashboardData after = dashboardService.getDashboard();

        assertThat(after.counts().resolvedToday()).isEqualTo(before.counts().resolvedToday());
        Map<LocalDate, DashboardData.DayDynamics> afterByDate = after.dynamics().stream()
                .collect(Collectors.toMap(DashboardData.DayDynamics::date, Function.identity()));
        Map<LocalDate, DashboardData.DayDynamics> beforeByDate = before.dynamics().stream()
                .collect(Collectors.toMap(DashboardData.DayDynamics::date, Function.identity()));
        LocalDate yesterdayDate = yesterday.toLocalDate();
        assertThat(afterByDate.get(yesterdayDate).resolved())
                .isEqualTo(beforeByDate.get(yesterdayDate).resolved() + 1);
        assertThat(afterByDate.get(LocalDate.now(ZoneOffset.UTC)).resolved())
                .isEqualTo(beforeByDate.get(LocalDate.now(ZoneOffset.UTC)).resolved());
    }
}
