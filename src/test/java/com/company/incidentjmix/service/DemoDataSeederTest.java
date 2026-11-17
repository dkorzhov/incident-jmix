package com.company.incidentjmix.service;

import com.company.incidentjmix.entity.Comment;
import com.company.incidentjmix.entity.HistoryEvent;
import com.company.incidentjmix.entity.HistoryEventType;
import com.company.incidentjmix.entity.Incident;
import com.company.incidentjmix.entity.IncidentStatus;
import com.company.incidentjmix.test_support.DomainCleanup;
import io.jmix.core.DataManager;
import io.jmix.core.security.SystemAuthenticator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Demo seed (BT-35, TASK.md §6). NO authentication extension here: seed()
 * is called bare, exactly like the startup thread does, proving the
 * SystemAuthenticator wrapper inside the seeder. Only test setup and
 * assertions are authenticated.
 */
@SpringBootTest
@ActiveProfiles("test")
class DemoDataSeederTest {

    @Autowired
    private DemoDataSeeder seeder;
    @Autowired
    private DashboardService dashboardService;
    @Autowired
    private AppUserService appUserService;
    @Autowired
    private DataManager dataManager;
    @Autowired
    private SystemAuthenticator systemAuthenticator;

    private DomainCleanup cleanup;

    @BeforeEach
    void setUp() {
        systemAuthenticator.runWithSystem(() -> {
            cleanup = new DomainCleanup(dataManager);
            cleanup.purgeAllIncidents();
        });
    }

    @AfterEach
    void tearDown() {
        systemAuthenticator.runWithSystem(() -> {
            cleanup.purgeAllIncidents();
            cleanup.cleanup();
        });
    }

    private List<Incident> allIncidents() {
        return dataManager.load(Incident.class).query("select e from inc_Incident e").list();
    }

    private Incident byTitle(String title) {
        return dataManager.load(Incident.class)
                .query("select e from inc_Incident e where e.title = :title")
                .parameter("title", title)
                .one();
    }

    @Test
    void seedCreatesDemoCompositionWithoutAuthentication() {
        // bare call — reproduces the startup thread with no authenticated user
        seeder.seed();

        systemAuthenticator.runWithSystem(() -> {
            List<Incident> incidents = allIncidents();
            assertThat(incidents).hasSize(6);
            assertThat(incidents).extracting(Incident::getStatus)
                    .filteredOn(s -> s == IncidentStatus.NEW).hasSize(2);
            assertThat(incidents).extracting(Incident::getStatus)
                    .filteredOn(s -> s == IncidentStatus.IN_PROGRESS).hasSize(2);
            assertThat(incidents).extracting(Incident::getStatus)
                    .filteredOn(s -> s == IncidentStatus.RESOLVED).hasSize(1);
            assertThat(incidents).extracting(Incident::getStatus)
                    .filteredOn(s -> s == IncidentStatus.CLOSED).hasSize(1);

            assertThat(appUserService.list()).hasSize(4);

            DashboardData dashboard = dashboardService.getDashboard();
            assertThat(dashboard.counts().open()).isEqualTo(5);
            assertThat(dashboard.counts().newCount()).isEqualTo(2);
            assertThat(dashboard.counts().inProgress()).isEqualTo(2);
            assertThat(dashboard.counts().critical()).isEqualTo(2);
            assertThat(dashboard.counts().resolvedToday()).isEqualTo(2);

            Incident mail = byTitle("Не работает почта");
            assertThat(mail.getStatus()).isEqualTo(IncidentStatus.IN_PROGRESS);
            assertThat(mail.getAssignee().getId()).isEqualTo(1);
            List<Comment> comments = dataManager.load(Comment.class)
                    .query("select e from inc_Comment e where e.incident.id = :id")
                    .parameter("id", mail.getId())
                    .list();
            assertThat(comments).hasSize(1);
            assertThat(comments.get(0).getAuthor().getId()).isEqualTo(1);

            Incident closed = byTitle("Заменить мышь");
            assertThat(closed.getStatus()).isEqualTo(IncidentStatus.CLOSED);
            assertThat(closed.getResolution()).isEqualTo("Мышь заменена на новую");
            List<HistoryEvent> closedHistory = dataManager.load(HistoryEvent.class)
                    .query("select e from inc_HistoryEvent e where e.incident.id = :id order by e.id asc")
                    .parameter("id", closed.getId())
                    .list();
            assertThat(closedHistory).extracting(HistoryEvent::getType).containsExactly(
                    HistoryEventType.CREATED, HistoryEventType.ASSIGNED, HistoryEventType.STATUS_CHANGED,
                    HistoryEventType.RESOLVED, HistoryEventType.STATUS_CHANGED);

            // every incident has a created event written by a real domain actor
            for (Incident incident : incidents) {
                long createdEvents = dataManager.loadValue(
                                "select count(e) from inc_HistoryEvent e where e.incident.id = :id and e.type = :type",
                                Long.class)
                        .parameter("id", incident.getId())
                        .parameter("type", HistoryEventType.CREATED.getId())
                        .one();
                assertThat(createdEvents).isEqualTo(1);
            }
        });
    }

    @Test
    void seedIsIdempotentWhenIncidentsExist() {
        seeder.seed();
        seeder.seed(); // second call must skip, not duplicate

        systemAuthenticator.runWithSystem(() -> assertThat(allIncidents()).hasSize(6));
    }

    @Test
    void readyEventDoesNothingWhenSeedDisabled() {
        // app.seed-demo defaults to false in the test profile
        seeder.onApplicationReady();

        systemAuthenticator.runWithSystem(() -> assertThat(allIncidents()).isEmpty());
    }
}
