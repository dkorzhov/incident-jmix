package com.company.incidentjmix.service;

import com.company.incidentjmix.entity.AppUser;
import com.company.incidentjmix.entity.Comment;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ExtendWith(AuthenticatedAsAdmin.class)
@ActiveProfiles("test")
class CommentServiceTest {

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
                new CreateCommand("Инцидент для комментариев", null, "Other", "Low", null));
        cleanup.track(incident);
        return incident;
    }

    @Test
    void addPersistsCommentWithAuthorAndDoesNotTouchUpdatedAt() {
        Incident incident = createNew();
        AppUser author = appUserService.getById(3);

        Comment comment = commentService.add(author, incident.getId(), "  Проверяю логи сервера  ");

        assertThat(comment.getId()).isNotNull();
        assertThat(comment.getText()).isEqualTo("Проверяю логи сервера");
        assertThat(comment.getAuthor().getId()).isEqualTo(3);
        assertThat(comment.getCreatedAt()).isNotNull();

        Incident reloaded = dataManager.load(Incident.class).id(incident.getId()).one();
        assertThat(reloaded.getUpdatedAt()).isEqualTo(incident.getUpdatedAt());

        Comment persisted = dataManager.load(Comment.class).id(comment.getId()).one();
        assertThat(persisted.getText()).isEqualTo("Проверяю логи сервера");
        assertThat(persisted.getIncident().getId()).isEqualTo(incident.getId());
    }

    @Test
    void addValidatesTextLength() {
        Incident incident = createNew();

        for (String bad : new String[]{null, "", "   "}) {
            assertThatThrownBy(() -> commentService.add(actor, incident.getId(), bad))
                    .isInstanceOfSatisfying(ValidationException.class, e ->
                            assertThat(e.getFieldErrors()).containsKey("text"));
        }
        assertThatThrownBy(() -> commentService.add(actor, incident.getId(), "x".repeat(2001)))
                .isInstanceOfSatisfying(ValidationException.class, e ->
                        assertThat(e.getFieldErrors()).containsKey("text"));

        Comment max = commentService.add(actor, incident.getId(), "x".repeat(2000));
        assertThat(max.getText()).hasSize(2000);

        long commentCount = dataManager.loadValue(
                        "select count(e) from inc_Comment e where e.incident.id = :id", Long.class)
                .parameter("id", incident.getId())
                .one();
        assertThat(commentCount).isEqualTo(1);
    }

    @Test
    void addToClosedIncidentIsConflict() {
        Incident incident = createNew();
        incidentService.changeStatus(actor, incident.getId(), IncidentStatus.IN_PROGRESS, null);
        incidentService.changeStatus(actor, incident.getId(), IncidentStatus.RESOLVED, "Решено");
        incidentService.changeStatus(actor, incident.getId(), IncidentStatus.CLOSED, null);

        assertThatThrownBy(() -> commentService.add(actor, incident.getId(), "Поздний комментарий"))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void addToUnknownIncidentIsNotFound() {
        assertThatThrownBy(() -> commentService.add(actor, "INC-999999", "Текст"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void commentsAllowedOnResolvedIncident() {
        Incident incident = createNew();
        incidentService.changeStatus(actor, incident.getId(), IncidentStatus.IN_PROGRESS, null);
        incidentService.changeStatus(actor, incident.getId(), IncidentStatus.RESOLVED, "Решено");

        Comment comment = commentService.add(actor, incident.getId(), "Подтверждаю решение");
        assertThat(comment.getId()).isNotNull();
    }
}
