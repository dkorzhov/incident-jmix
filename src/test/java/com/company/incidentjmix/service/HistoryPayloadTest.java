package com.company.incidentjmix.service;

import com.company.incidentjmix.entity.AppUser;
import com.company.incidentjmix.entity.Comment;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Automatic history writing: event types and «before/after» payloads per
 * the ADR-005 table (Q-03), actor/createdAt server-filled, no events on
 * failed or no-op operations.
 */
@SpringBootTest
@ExtendWith(AuthenticatedAsAdmin.class)
@ActiveProfiles("test")
class HistoryPayloadTest {

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

    private Incident create(String title, Integer assigneeId) {
        Incident incident = incidentService.create(actor,
                new CreateCommand(title, "описание", "Mail", "High", assigneeId));
        cleanup.track(incident);
        return incident;
    }

    private List<HistoryEvent> eventsOf(String incidentId) {
        return dataManager.load(HistoryEvent.class)
                .query("select e from inc_HistoryEvent e where e.incident.id = :id order by e.id asc")
                .parameter("id", incidentId)
                .fetchPlan(fp -> fp.addFetchPlan(io.jmix.core.FetchPlan.BASE).add("actor", io.jmix.core.FetchPlan.BASE))
                .list();
    }

    private JsonNode payload(HistoryEvent event) {
        return objectMapper.readTree(event.getPayload());
    }

    @Test
    void createdEventPayloadContainsAllInitialFields() {
        Incident incident = create("История создания", null);

        List<HistoryEvent> events = eventsOf(incident.getId());
        assertThat(events).hasSize(1);
        HistoryEvent created = events.get(0);
        assertThat(created.getType()).isEqualTo(HistoryEventType.CREATED);
        assertThat(created.getActor().getId()).isEqualTo(actor.getId());
        assertThat(created.getCreatedAt()).isBetween(
                incident.getCreatedAt().minusSeconds(5),
                OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(5));

        JsonNode after = payload(created).get("after");
        assertThat(after.get("title").asString()).isEqualTo("История создания");
        assertThat(after.get("description").asString()).isEqualTo("описание");
        assertThat(after.get("category").asString()).isEqualTo("Mail");
        assertThat(after.get("priority").asString()).isEqualTo("High");
        assertThat(after.get("status").asString()).isEqualTo("New");
        assertThat(after.get("assignee_id").isNull()).isTrue();
        assertThat(payload(created).has("before")).isFalse();
    }

    @Test
    void createdEventPayloadCarriesAssigneeId() {
        Incident incident = create("История с ответственным", 2);
        JsonNode after = payload(eventsOf(incident.getId()).get(0)).get("after");
        assertThat(after.get("assignee_id").asInt()).isEqualTo(2);
    }

    @Test
    void combinedPatchWritesOneEventPerTypeWithChangedFieldsOnly() {
        Incident incident = create("Комбинированный патч", null);

        incidentService.update(actor, incident.getId(), new PatchCommand()
                .title("Новый комбинированный заголовок")
                .priority("Critical")
                .assigneeId(2));

        List<HistoryEvent> events = eventsOf(incident.getId());
        assertThat(events).extracting(HistoryEvent::getType).containsExactly(
                HistoryEventType.CREATED, HistoryEventType.FIELDS_UPDATED,
                HistoryEventType.PRIORITY_CHANGED, HistoryEventType.ASSIGNED);

        JsonNode fields = payload(events.get(1));
        assertThat(fields.get("before").get("title").asString()).isEqualTo("Комбинированный патч");
        assertThat(fields.get("after").get("title").asString()).isEqualTo("Новый комбинированный заголовок");
        assertThat(fields.get("before").has("description")).isFalse();
        assertThat(fields.get("before").has("category")).isFalse();
        assertThat(fields.get("after").has("priority")).isFalse();
        assertThat(fields.get("after").has("assignee_id")).isFalse();

        JsonNode priority = payload(events.get(2));
        assertThat(priority.get("before").get("priority").asString()).isEqualTo("High");
        assertThat(priority.get("after").get("priority").asString()).isEqualTo("Critical");

        JsonNode assigned = payload(events.get(3));
        assertThat(assigned.get("before").get("assignee_id").isNull()).isTrue();
        assertThat(assigned.get("after").get("assignee_id").asInt()).isEqualTo(2);

        assertThat(events).allSatisfy(e -> assertThat(e.getActor().getId()).isEqualTo(actor.getId()));
    }

    @Test
    void unassignWritesAssignedEventWithNullAfter() {
        Incident incident = create("Снятие ответственного", 1);

        incidentService.update(actor, incident.getId(), new PatchCommand().assigneeId(null));

        List<HistoryEvent> events = eventsOf(incident.getId());
        assertThat(events).hasSize(2);
        JsonNode assigned = payload(events.get(1));
        assertThat(events.get(1).getType()).isEqualTo(HistoryEventType.ASSIGNED);
        assertThat(assigned.get("before").get("assignee_id").asInt()).isEqualTo(1);
        assertThat(assigned.get("after").get("assignee_id").isNull()).isTrue();
    }

    @Test
    void resolvedEventIsSingleAndCarriesResolutionBeforeAfter() {
        Incident incident = create("История решения", null);
        incidentService.changeStatus(actor, incident.getId(), IncidentStatus.IN_PROGRESS, null);
        incidentService.changeStatus(actor, incident.getId(), IncidentStatus.RESOLVED, "Починили конфиг");

        List<HistoryEvent> events = eventsOf(incident.getId());
        assertThat(events).extracting(HistoryEvent::getType).containsExactly(
                HistoryEventType.CREATED, HistoryEventType.STATUS_CHANGED, HistoryEventType.RESOLVED);

        JsonNode resolved = payload(events.get(2));
        assertThat(resolved.get("before").get("status").asString()).isEqualTo("In Progress");
        assertThat(resolved.get("before").get("resolution").isNull()).isTrue();
        assertThat(resolved.get("after").get("status").asString()).isEqualTo("Resolved");
        assertThat(resolved.get("after").get("resolution").asString()).isEqualTo("Починили конфиг");

        JsonNode inProgress = payload(events.get(1));
        assertThat(inProgress.get("before").get("status").asString()).isEqualTo("New");
        assertThat(inProgress.get("after").get("status").asString()).isEqualTo("In Progress");
    }

    @Test
    void commentedEventCarriesCommentIdAndText() {
        Incident incident = create("История комментария", null);
        AppUser anotherActor = appUserService.getById(2);

        Comment comment = commentService.add(anotherActor, incident.getId(), "Текст комментария");

        List<HistoryEvent> events = eventsOf(incident.getId());
        assertThat(events).hasSize(2);
        HistoryEvent commented = events.get(1);
        assertThat(commented.getType()).isEqualTo(HistoryEventType.COMMENTED);
        assertThat(commented.getActor().getId()).isEqualTo(2);

        JsonNode after = payload(commented).get("after");
        assertThat(after.get("comment_id").asInt()).isEqualTo(comment.getId());
        assertThat(after.get("text").asString()).isEqualTo("Текст комментария");
        assertThat(payload(commented).has("before")).isFalse();
    }

    @Test
    void failedOperationsWriteNoHistory() {
        Incident incident = create("Неудачные операции", null);
        long before = eventsOf(incident.getId()).size();

        assertThatThrownBy(() -> incidentService.changeStatus(actor, incident.getId(), IncidentStatus.CLOSED, null))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> incidentService.update(actor, incident.getId(), new PatchCommand().title("abc")))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> commentService.add(actor, incident.getId(), " "))
                .isInstanceOf(ValidationException.class);

        assertThat(eventsOf(incident.getId())).hasSize((int) before);
    }

    @Test
    void noOpPatchWritesNoHistory() {
        Incident incident = create("Пустой патч", null);

        incidentService.update(actor, incident.getId(), new PatchCommand()
                .title("Пустой патч").priority("High").assigneeId(null));

        assertThat(eventsOf(incident.getId())).extracting(HistoryEvent::getType)
                .containsExactly(HistoryEventType.CREATED);
    }
}
