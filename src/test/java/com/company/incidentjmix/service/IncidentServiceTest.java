package com.company.incidentjmix.service;

import com.company.incidentjmix.entity.AppUser;
import com.company.incidentjmix.entity.HistoryEvent;
import com.company.incidentjmix.entity.HistoryEventType;
import com.company.incidentjmix.entity.Incident;
import com.company.incidentjmix.entity.IncidentCategory;
import com.company.incidentjmix.entity.IncidentPriority;
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

@SpringBootTest
@ExtendWith(AuthenticatedAsAdmin.class)
@ActiveProfiles("test")
class IncidentServiceTest {

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

    private Incident create(String title, String category, String priority, Integer assigneeId) {
        Incident incident = incidentService.create(actor,
                new CreateCommand(title, null, category, priority, assigneeId));
        cleanup.track(incident);
        return incident;
    }

    private long incidentCount() {
        return dataManager.loadValue("select count(e) from inc_Incident e", Long.class).one();
    }

    @Test
    void createAssignsSequentialHumanReadableIdAndServerFields() {
        long next = cleanup.counterValue();

        Incident first = create("Не работает почта", "Mail", "High", null);
        Incident second = create("Проблемы с VPN", "Network", "Critical", 2);

        assertThat(first.getId()).isEqualTo(String.format("INC-%03d", next));
        assertThat(second.getId()).isEqualTo(String.format("INC-%03d", next + 1));
        assertThat(cleanup.counterValue()).isEqualTo(next + 2);

        assertThat(first.getStatus()).isEqualTo(IncidentStatus.NEW);
        assertThat(first.getCategory()).isEqualTo(IncidentCategory.MAIL);
        assertThat(first.getPriority()).isEqualTo(IncidentPriority.HIGH);
        assertThat(first.getAssignee()).isNull();
        assertThat(first.getResolution()).isNull();
        assertThat(first.getCreatedAt()).isNotNull();
        assertThat(first.getUpdatedAt()).isEqualTo(first.getCreatedAt());
        assertThat(second.getAssignee().getId()).isEqualTo(2);

        List<HistoryEvent> events = dataManager.load(HistoryEvent.class)
                .query("select e from inc_HistoryEvent e where e.incident.id = :id")
                .parameter("id", first.getId())
                .list();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getType()).isEqualTo(HistoryEventType.CREATED);
        assertThat(events.get(0).getActor().getId()).isEqualTo(actor.getId());
    }

    @Test
    void createAggregatesFieldValidationErrorsAndDoesNotConsumeCounter() {
        long next = cleanup.counterValue();
        long countBefore = incidentCount();

        assertThatThrownBy(() -> incidentService.create(actor,
                new CreateCommand("abc", null, "Bogus", null, 999)))
                .isInstanceOfSatisfying(ValidationException.class, e ->
                        assertThat(e.getFieldErrors()).containsOnlyKeys(
                                "title", "category", "priority", "assignee_id"));

        assertThat(cleanup.counterValue()).isEqualTo(next);
        assertThat(incidentCount()).isEqualTo(countBefore);
    }

    @Test
    void createValidatesFieldLengthBoundaries() {
        Incident min = create("a".repeat(5), "Other", "Low", null);
        assertThat(min.getTitle()).hasSize(5);
        Incident max = create("a".repeat(120), "Other", "Low", null);
        assertThat(max.getTitle()).hasSize(120);

        assertThatThrownBy(() -> incidentService.create(actor,
                new CreateCommand("a".repeat(4), null, "Other", "Low", null)))
                .isInstanceOfSatisfying(ValidationException.class, e ->
                        assertThat(e.getFieldErrors()).containsKey("title"));
        assertThatThrownBy(() -> incidentService.create(actor,
                new CreateCommand("a".repeat(121), null, "Other", "Low", null)))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> incidentService.create(actor,
                new CreateCommand("Валидный заголовок", "x".repeat(2001), "Other", "Low", null)))
                .isInstanceOfSatisfying(ValidationException.class, e ->
                        assertThat(e.getFieldErrors()).containsKey("description"));
        assertThatThrownBy(() -> incidentService.create(actor,
                new CreateCommand(null, null, "Other", "Low", null)))
                .isInstanceOfSatisfying(ValidationException.class, e ->
                        assertThat(e.getFieldErrors()).containsKey("title"));
    }

    @Test
    void createTrimsTitleAndKeepsDescriptionAsIs() {
        Incident incident = incidentService.create(actor,
                new CreateCommand("  Проблемы с сетью  ", "детали", "Network", "Medium", null));
        cleanup.track(incident);

        assertThat(incident.getTitle()).isEqualTo("Проблемы с сетью");
        assertThat(incident.getDescription()).isEqualTo("детали");
    }

    @Test
    void listFiltersByStatusPriorityAndSearchesByIdAndTitle() {
        Incident alpha = create("Альфа сетевой сбой", "Network", "Critical", null);
        Incident beta = create("Бета почта не работает", "Mail", "Low", null);
        Incident gamma = create("Гамма база данных", "Database", "High", 2);
        incidentService.changeStatus(actor, gamma.getId(), IncidentStatus.IN_PROGRESS, null);

        assertThat(incidentService.list(null, IncidentStatus.NEW, null))
                .extracting(Incident::getId)
                .containsExactly(beta.getId(), alpha.getId());
        assertThat(incidentService.list(null, null, IncidentPriority.CRITICAL))
                .extracting(Incident::getId)
                .containsExactly(alpha.getId());
        assertThat(incidentService.list(null, IncidentStatus.IN_PROGRESS, IncidentPriority.HIGH))
                .extracting(Incident::getId)
                .containsExactly(gamma.getId());

        assertThat(incidentService.list("альфа сетевой", null, null))
                .extracting(Incident::getId)
                .containsExactly(alpha.getId());
        String idFragment = alpha.getId().substring(4);
        assertThat(incidentService.list(idFragment, null, null))
                .extracting(Incident::getId)
                .contains(alpha.getId());

        // assignee is fetched for detached access
        List<Incident> inProgress = incidentService.list(null, IncidentStatus.IN_PROGRESS, null);
        assertThat(inProgress).hasSize(1);
        assertThat(inProgress.get(0).getAssignee().getName()).isEqualTo("Петров П.П.");
    }

    @Test
    void listSearchTreatsPercentAndUnderscoreAsLiterals() {
        Incident special = create("Скидка 100% на всё", "Other", "Low", null);
        create("Обычный инцидент без символов", "Other", "Low", null);

        assertThat(incidentService.list("100%", null, null))
                .extracting(Incident::getId)
                .containsExactly(special.getId());
    }

    @Test
    void getReturnsCardWithCommentsAscendingAndHistoryDescending() {
        Incident incident = create("Карточка с историей", "Portal", "Medium", null);
        commentService.add(actor, incident.getId(), "Первый комментарий");
        commentService.add(actor, incident.getId(), "Второй комментарий");
        incidentService.changeStatus(actor, incident.getId(), IncidentStatus.IN_PROGRESS, null);

        IncidentDetails details = incidentService.get(incident.getId());

        assertThat(details.incident().getId()).isEqualTo(incident.getId());
        assertThat(details.comments()).extracting(c -> c.getText())
                .containsExactly("Первый комментарий", "Второй комментарий");
        assertThat(details.comments().get(0).getAuthor().getName()).isEqualTo("Иванов И.И.");
        assertThat(details.history()).hasSize(4);
        assertThat(details.history().get(0).getType()).isEqualTo(HistoryEventType.STATUS_CHANGED);
        assertThat(details.history().get(3).getType()).isEqualTo(HistoryEventType.CREATED);
        assertThat(details.history().get(0).getActor().getId()).isEqualTo(actor.getId());

        assertThatThrownBy(() -> incidentService.get("INC-999999"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void updateChangesFieldsBumpsUpdatedAtAndWritesFieldsUpdated() {
        Incident incident = create("Старый заголовок", "Mail", "Low", null);

        Incident updated = incidentService.update(actor, incident.getId(),
                new PatchCommand().title("Новый заголовок").description("Новые детали").category("Database"));
        cleanup.track(updated);

        assertThat(updated.getTitle()).isEqualTo("Новый заголовок");
        assertThat(updated.getDescription()).isEqualTo("Новые детали");
        assertThat(updated.getCategory()).isEqualTo(IncidentCategory.DATABASE);
        assertThat(updated.getUpdatedAt()).isAfterOrEqualTo(incident.getCreatedAt());

        List<HistoryEvent> events = dataManager.load(HistoryEvent.class)
                .query("select e from inc_HistoryEvent e where e.incident.id = :id order by e.id asc")
                .parameter("id", incident.getId())
                .list();
        assertThat(events).extracting(HistoryEvent::getType)
                .containsExactly(HistoryEventType.CREATED, HistoryEventType.FIELDS_UPDATED);
    }

    @Test
    void updateNoOpKeepsUpdatedAtAndWritesNoEvents() {
        Incident incident = create("Заголовок без изменений", "Mail", "Low", null);

        Incident result = incidentService.update(actor, incident.getId(),
                new PatchCommand().title("Заголовок без изменений"));

        assertThat(result.getUpdatedAt()).isEqualTo(incident.getUpdatedAt());
        long eventCount = dataManager.loadValue(
                        "select count(e) from inc_HistoryEvent e where e.incident.id = :id", Long.class)
                .parameter("id", incident.getId())
                .one();
        assertThat(eventCount).isEqualTo(1); // only 'created'
    }

    @Test
    void updateAssigneeToNullUnassigns() {
        Incident incident = create("Назначенный инцидент", "Mail", "Low", 1);
        assertThat(incident.getAssignee()).isNotNull();

        Incident updated = incidentService.update(actor, incident.getId(),
                new PatchCommand().assigneeId(null));

        assertThat(updated.getAssignee()).isNull();
        List<HistoryEvent> events = dataManager.load(HistoryEvent.class)
                .query("select e from inc_HistoryEvent e where e.incident.id = :id order by e.id asc")
                .parameter("id", incident.getId())
                .list();
        assertThat(events).extracting(HistoryEvent::getType)
                .containsExactly(HistoryEventType.CREATED, HistoryEventType.ASSIGNED);
    }

    @Test
    void updateUnknownIdThrowsNotFound() {
        assertThatThrownBy(() -> incidentService.update(actor, "INC-999999",
                new PatchCommand().title("Что-нибудь")))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void updateValidatesPresentFields() {
        Incident incident = create("Валидный заголовок", "Mail", "Low", null);

        assertThatThrownBy(() -> incidentService.update(actor, incident.getId(),
                new PatchCommand().title("abc").priority("Bogus").assigneeId(999)))
                .isInstanceOfSatisfying(ValidationException.class, e ->
                        assertThat(e.getFieldErrors()).containsOnlyKeys("title", "priority", "assignee_id"));

        Incident reloaded = dataManager.load(Incident.class).id(incident.getId()).one();
        assertThat(reloaded.getTitle()).isEqualTo("Валидный заголовок");
        assertThat(reloaded.getPriority()).isEqualTo(IncidentPriority.LOW);
    }
}
