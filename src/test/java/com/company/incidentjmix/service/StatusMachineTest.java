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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * State automaton (TASK.md §2): success path, 409 on invalid transitions,
 * mandatory resolution on Resolved, Closed immutability.
 */
@SpringBootTest
@ExtendWith(AuthenticatedAsAdmin.class)
@ActiveProfiles("test")
class StatusMachineTest {

    @Autowired
    private IncidentService incidentService;
    @Autowired
    private CommentService commentService;
    @Autowired
    private AppUserService appUserService;
    @Autowired
    private DataManager dataManager;

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

    private Incident createNew() {
        Incident incident = incidentService.create(actor,
                new CreateCommand("Инцидент для автомата", null, "Other", "Medium", null));
        cleanup.track(incident);
        return incident;
    }

    private Incident bringTo(Incident incident, IncidentStatus target) {
        if (target == IncidentStatus.NEW) {
            return incident;
        }
        Incident result = incidentService.changeStatus(actor, incident.getId(), IncidentStatus.IN_PROGRESS, null);
        if (target == IncidentStatus.IN_PROGRESS) {
            return result;
        }
        result = incidentService.changeStatus(actor, incident.getId(), IncidentStatus.RESOLVED, "Решение проблемы");
        if (target == IncidentStatus.RESOLVED) {
            return result;
        }
        return incidentService.changeStatus(actor, incident.getId(), IncidentStatus.CLOSED, null);
    }

    private List<HistoryEvent> eventsOf(String incidentId) {
        return dataManager.load(HistoryEvent.class)
                .query("select e from inc_HistoryEvent e where e.incident.id = :id order by e.id asc")
                .parameter("id", incidentId)
                .list();
    }

    @Test
    void happyPathNewInProgressResolvedClosed() {
        Incident incident = createNew();

        Incident inProgress = incidentService.changeStatus(actor, incident.getId(), IncidentStatus.IN_PROGRESS, null);
        assertThat(inProgress.getStatus()).isEqualTo(IncidentStatus.IN_PROGRESS);
        assertThat(inProgress.getResolution()).isNull();

        Incident resolved = incidentService.changeStatus(actor, incident.getId(), IncidentStatus.RESOLVED,
                "  Перезапущен сервис, очередь разобрана  ");
        assertThat(resolved.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(resolved.getResolution()).isEqualTo("Перезапущен сервис, очередь разобрана");

        Incident closed = incidentService.changeStatus(actor, incident.getId(), IncidentStatus.CLOSED, null);
        assertThat(closed.getStatus()).isEqualTo(IncidentStatus.CLOSED);
        assertThat(closed.getResolution()).isEqualTo("Перезапущен сервис, очередь разобрана");

        assertThat(eventsOf(incident.getId())).extracting(HistoryEvent::getType)
                .containsExactly(HistoryEventType.CREATED, HistoryEventType.STATUS_CHANGED,
                        HistoryEventType.RESOLVED, HistoryEventType.STATUS_CHANGED);
    }

    @Test
    void invalidTransitionsAndSameStatusAreConflict() {
        // New → Resolved / Closed
        Incident fresh = createNew();
        assertThatThrownBy(() -> incidentService.changeStatus(actor, fresh.getId(), IncidentStatus.RESOLVED, "решение"))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> incidentService.changeStatus(actor, fresh.getId(), IncidentStatus.CLOSED, null))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> incidentService.changeStatus(actor, fresh.getId(), IncidentStatus.NEW, null))
                .isInstanceOf(ConflictException.class);

        // In Progress → New / Closed / same
        Incident inProgress = bringTo(createNew(), IncidentStatus.IN_PROGRESS);
        assertThatThrownBy(() -> incidentService.changeStatus(actor, inProgress.getId(), IncidentStatus.NEW, null))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> incidentService.changeStatus(actor, inProgress.getId(), IncidentStatus.CLOSED, null))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> incidentService.changeStatus(actor, inProgress.getId(), IncidentStatus.IN_PROGRESS, null))
                .isInstanceOf(ConflictException.class);

        // Resolved → New / In Progress / same
        Incident resolved = bringTo(createNew(), IncidentStatus.RESOLVED);
        assertThatThrownBy(() -> incidentService.changeStatus(actor, resolved.getId(), IncidentStatus.NEW, null))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> incidentService.changeStatus(actor, resolved.getId(), IncidentStatus.IN_PROGRESS, null))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> incidentService.changeStatus(actor, resolved.getId(), IncidentStatus.RESOLVED, "ещё"))
                .isInstanceOf(ConflictException.class);

        // failed transitions write no history and change nothing
        assertThat(eventsOf(fresh.getId())).extracting(HistoryEvent::getType)
                .containsExactly(HistoryEventType.CREATED);
        Incident reloaded = dataManager.load(Incident.class).id(fresh.getId()).one();
        assertThat(reloaded.getStatus()).isEqualTo(IncidentStatus.NEW);
    }

    @Test
    void resolveWithoutNonBlankResolutionIsValidationError() {
        Incident incident = bringTo(createNew(), IncidentStatus.IN_PROGRESS);

        for (String bad : new String[]{null, "", "   "}) {
            assertThatThrownBy(() -> incidentService.changeStatus(actor, incident.getId(), IncidentStatus.RESOLVED, bad))
                    .isInstanceOfSatisfying(ValidationException.class, e ->
                            assertThat(e.getFieldErrors()).containsKey("resolution"));
        }
        assertThatThrownBy(() -> incidentService.changeStatus(actor, incident.getId(), IncidentStatus.RESOLVED,
                "x".repeat(2001)))
                .isInstanceOfSatisfying(ValidationException.class, e ->
                        assertThat(e.getFieldErrors()).containsKey("resolution"));

        Incident reloaded = dataManager.load(Incident.class).id(incident.getId()).one();
        assertThat(reloaded.getStatus()).isEqualTo(IncidentStatus.IN_PROGRESS);
        assertThat(reloaded.getResolution()).isNull();
        assertThat(eventsOf(incident.getId())).extracting(HistoryEvent::getType)
                .containsExactly(HistoryEventType.CREATED, HistoryEventType.STATUS_CHANGED);
    }

    @Test
    void closedIncidentIsImmutableForPatchStatusAndComments() {
        Incident closed = bringTo(createNew(), IncidentStatus.CLOSED);
        long eventCount = eventsOf(closed.getId()).size();

        assertThatThrownBy(() -> incidentService.update(actor, closed.getId(),
                new PatchCommand().title("Попытка изменения")))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> incidentService.changeStatus(actor, closed.getId(), IncidentStatus.IN_PROGRESS, null))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> incidentService.changeStatus(actor, closed.getId(), IncidentStatus.RESOLVED, "решение"))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> commentService.add(actor, closed.getId(), "Комментарий в закрытый"))
                .isInstanceOf(ConflictException.class);

        Incident reloaded = dataManager.load(Incident.class).id(closed.getId()).one();
        assertThat(reloaded.getStatus()).isEqualTo(IncidentStatus.CLOSED);
        assertThat(reloaded.getTitle()).isEqualTo("Инцидент для автомата");
        assertThat(eventsOf(closed.getId())).hasSize((int) eventCount);
    }

    @Test
    void resolutionIsIgnoredOnNonResolvedTransitions() {
        Incident incident = createNew();

        Incident inProgress = incidentService.changeStatus(actor, incident.getId(),
                IncidentStatus.IN_PROGRESS, "это не должно сохраниться");

        assertThat(inProgress.getResolution()).isNull();
        assertThat(eventsOf(incident.getId())).extracting(HistoryEvent::getType)
                .containsExactly(HistoryEventType.CREATED, HistoryEventType.STATUS_CHANGED);
    }

    @Test
    void resolutionIsWriteOnceAndSurvivesLaterFieldUpdates() {
        Incident incident = bringTo(createNew(), IncidentStatus.RESOLVED);

        Incident updated = incidentService.update(actor, incident.getId(),
                new PatchCommand().title("Уточнённый заголовок"));

        assertThat(updated.getResolution()).isEqualTo("Решение проблемы");
        assertThat(updated.getTitle()).isEqualTo("Уточнённый заголовок");
    }

    @Test
    void unknownTargetStatusIsValidationError() {
        Incident incident = createNew();
        assertThatThrownBy(() -> incidentService.changeStatus(actor, incident.getId(), null, null))
                .isInstanceOfSatisfying(ValidationException.class, e ->
                        assertThat(e.getFieldErrors()).containsKey("status"));
    }

    @Test
    void statusChangeOfUnknownIncidentIsNotFound() {
        assertThatThrownBy(() -> incidentService.changeStatus(actor, "INC-999999", IncidentStatus.IN_PROGRESS, null))
                .isInstanceOf(NotFoundException.class);
    }
}
