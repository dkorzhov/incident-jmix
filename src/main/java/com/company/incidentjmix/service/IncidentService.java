package com.company.incidentjmix.service;

import com.company.incidentjmix.entity.AppUser;
import com.company.incidentjmix.entity.Comment;
import com.company.incidentjmix.entity.HistoryEvent;
import com.company.incidentjmix.entity.HistoryEventType;
import com.company.incidentjmix.entity.Incident;
import com.company.incidentjmix.entity.IncidentCategory;
import com.company.incidentjmix.entity.IncidentCounter;
import com.company.incidentjmix.entity.IncidentPriority;
import com.company.incidentjmix.entity.IncidentStatus;
import io.jmix.core.DataManager;
import io.jmix.core.FetchPlan;
import io.jmix.core.FluentLoader;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Incident lifecycle and business rules (TASK.md §2, ADR-005). Single
 * validation point shared by REST and UI (NFR-7). Every significant change
 * is recorded in history inside the same transaction; a domain exception
 * rolls back both data and history (AC-HIS-1).
 */
@Service
public class IncidentService {

    public static final String COUNTER_NAME = "incident";
    public static final String CLOSED_MESSAGE = "Инцидент закрыт и не может быть изменён";

    private final DataManager dataManager;
    private final HistoryService historyService;
    private final AppUserService appUserService;

    @PersistenceContext
    private EntityManager entityManager;

    public IncidentService(DataManager dataManager, HistoryService historyService, AppUserService appUserService) {
        this.dataManager = dataManager;
        this.historyService = historyService;
        this.appUserService = appUserService;
    }

    /**
     * Creates an incident with the next gap-free INC-NNN id. The counter row
     * is pessimistically locked, so concurrent creates serialize and a
     * rollback returns the id to the pool (BT-34). Writes the created event.
     */
    @Transactional
    public Incident create(AppUser actor, CreateCommand command) {
        Map<String, String> errors = new LinkedHashMap<>();
        String title = validateTitle(command.title(), errors);
        validateDescription(command.description(), errors);
        IncidentCategory category = validateCategory(command.category(), errors);
        IncidentPriority priority = validatePriority(command.priority(), errors);
        AppUser assignee = validateAssignee(command.assigneeId(), errors);
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }

        IncidentCounter counter = entityManager.find(IncidentCounter.class, COUNTER_NAME, LockModeType.PESSIMISTIC_WRITE);
        if (counter == null) {
            throw new IllegalStateException("Строка счётчика 'incident' отсутствует в INC_ID_COUNTER");
        }
        long next = counter.getNextValue();
        String id = String.format("INC-%03d", next);
        counter.setNextValue(next + 1);

        OffsetDateTime now = nowUtc();
        Incident incident = dataManager.create(Incident.class);
        incident.setId(id);
        incident.setTitle(title);
        incident.setDescription(command.description());
        incident.setCategory(category);
        incident.setPriority(priority);
        incident.setStatus(IncidentStatus.NEW);
        incident.setAssignee(assignee);
        incident.setCreatedAt(now);
        incident.setUpdatedAt(now);
        Incident saved = dataManager.save(incident);

        Map<String, Object> after = new HashMap<>();
        after.put("title", saved.getTitle());
        after.put("description", saved.getDescription());
        after.put("category", category.getId());
        after.put("priority", priority.getId());
        after.put("status", IncidentStatus.NEW.getId());
        after.put("assignee_id", assignee == null ? null : assignee.getId());
        historyService.record(saved, actor, HistoryEventType.CREATED, Map.of("after", after));
        return saved;
    }

    /**
     * Partial field update. Absent fields stay unchanged; explicit null
     * clears description / unassigns assignee. A no-op changes neither
     * updatedAt nor history. Changed title/description/category aggregate
     * into one fields_updated event; priority and assignee get their own
     * typed events (ADR-005 payload table).
     */
    @Transactional
    public Incident update(AppUser actor, String id, PatchCommand command) {
        Incident incident = loadForChange(id);
        if (incident.getStatus() == IncidentStatus.CLOSED) {
            throw new ConflictException(CLOSED_MESSAGE);
        }

        Map<String, String> errors = new LinkedHashMap<>();
        String newTitle = null;
        if (command.isTitlePresent()) {
            newTitle = validateTitle(command.getTitle(), errors);
        }
        if (command.isDescriptionPresent()) {
            validateDescription(command.getDescription(), errors);
        }
        IncidentCategory newCategory = null;
        if (command.isCategoryPresent()) {
            newCategory = validateCategory(command.getCategory(), errors);
        }
        IncidentPriority newPriority = null;
        if (command.isPriorityPresent()) {
            newPriority = validatePriority(command.getPriority(), errors);
        }
        AppUser newAssignee = null;
        if (command.isAssigneeIdPresent()) {
            newAssignee = validateAssignee(command.getAssigneeId(), errors);
        }
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }

        boolean titleChanged = command.isTitlePresent() && !Objects.equals(newTitle, incident.getTitle());
        boolean descriptionChanged = command.isDescriptionPresent()
                && !Objects.equals(command.getDescription(), incident.getDescription());
        boolean categoryChanged = command.isCategoryPresent() && newCategory != incident.getCategory();
        boolean priorityChanged = command.isPriorityPresent() && newPriority != incident.getPriority();
        Integer oldAssigneeId = incident.getAssignee() == null ? null : incident.getAssignee().getId();
        Integer newAssigneeId = newAssignee == null ? null : newAssignee.getId();
        boolean assigneeChanged = command.isAssigneeIdPresent() && !Objects.equals(newAssigneeId, oldAssigneeId);

        if (!titleChanged && !descriptionChanged && !categoryChanged && !priorityChanged && !assigneeChanged) {
            return incident;
        }

        Map<String, Object> fieldsBefore = new HashMap<>();
        Map<String, Object> fieldsAfter = new HashMap<>();
        if (titleChanged) {
            fieldsBefore.put("title", incident.getTitle());
            fieldsAfter.put("title", newTitle);
            incident.setTitle(newTitle);
        }
        if (descriptionChanged) {
            fieldsBefore.put("description", incident.getDescription());
            fieldsAfter.put("description", command.getDescription());
            incident.setDescription(command.getDescription());
        }
        if (categoryChanged) {
            fieldsBefore.put("category", incident.getCategory().getId());
            fieldsAfter.put("category", newCategory.getId());
            incident.setCategory(newCategory);
        }
        String oldPriorityId = incident.getPriority().getId();
        if (priorityChanged) {
            incident.setPriority(newPriority);
        }
        if (assigneeChanged) {
            incident.setAssignee(newAssignee);
        }
        incident.setUpdatedAt(nowUtc());
        Incident saved = dataManager.save(incident);

        if (!fieldsBefore.isEmpty()) {
            historyService.record(saved, actor, HistoryEventType.FIELDS_UPDATED,
                    Map.of("before", fieldsBefore, "after", fieldsAfter));
        }
        if (priorityChanged) {
            historyService.record(saved, actor, HistoryEventType.PRIORITY_CHANGED,
                    Map.of("before", Map.of("priority", oldPriorityId),
                            "after", Map.of("priority", newPriority.getId())));
        }
        if (assigneeChanged) {
            Map<String, Object> before = new HashMap<>();
            before.put("assignee_id", oldAssigneeId);
            Map<String, Object> after = new HashMap<>();
            after.put("assignee_id", newAssigneeId);
            historyService.record(saved, actor, HistoryEventType.ASSIGNED,
                    Map.of("before", before, "after", after));
        }
        return saved;
    }

    /**
     * Status automaton (TASK.md §2): New → In Progress → Resolved → Closed.
     * Resolved requires a non-blank resolution (400); resolution is
     * write-once and ignored on other transitions. Invalid transitions,
     * same status and any change of Closed → ConflictException (409).
     */
    @Transactional
    public Incident changeStatus(AppUser actor, String id, @Nullable IncidentStatus target, @Nullable String resolution) {
        Incident incident = loadForChange(id);
        if (target == null) {
            throw new ValidationException("status", "неизвестное значение");
        }
        IncidentStatus current = incident.getStatus();
        if (current == IncidentStatus.CLOSED) {
            throw new ConflictException(CLOSED_MESSAGE);
        }
        if (current == target) {
            throw new ConflictException("Инцидент уже в статусе " + target.getId());
        }
        boolean allowed = (current == IncidentStatus.NEW && target == IncidentStatus.IN_PROGRESS)
                || (current == IncidentStatus.IN_PROGRESS && target == IncidentStatus.RESOLVED)
                || (current == IncidentStatus.RESOLVED && target == IncidentStatus.CLOSED);
        if (!allowed) {
            throw new ConflictException("Недопустимый переход статуса: " + current.getId() + " → " + target.getId());
        }

        String trimmedResolution = resolution == null ? null : resolution.trim();
        if (trimmedResolution != null && trimmedResolution.length() > 2000) {
            throw new ValidationException("resolution", "длина не более 2000 символов");
        }
        if (target == IncidentStatus.RESOLVED && (trimmedResolution == null || trimmedResolution.isEmpty())) {
            throw new ValidationException("resolution", "обязательно при переходе в Resolved");
        }

        String oldStatusId = current.getId();
        String oldResolution = incident.getResolution();
        incident.setStatus(target);
        if (target == IncidentStatus.RESOLVED) {
            incident.setResolution(trimmedResolution);
        }
        incident.setUpdatedAt(nowUtc());
        Incident saved = dataManager.save(incident);

        if (target == IncidentStatus.RESOLVED) {
            Map<String, Object> before = new HashMap<>();
            before.put("status", oldStatusId);
            before.put("resolution", oldResolution);
            Map<String, Object> after = new HashMap<>();
            after.put("status", target.getId());
            after.put("resolution", trimmedResolution);
            historyService.record(saved, actor, HistoryEventType.RESOLVED,
                    Map.of("before", before, "after", after));
        } else {
            historyService.record(saved, actor, HistoryEventType.STATUS_CHANGED,
                    Map.of("before", Map.of("status", oldStatusId),
                            "after", Map.of("status", target.getId())));
        }
        return saved;
    }

    /**
     * Registry list: case-insensitive substring search on id or title,
     * exact enum filters combined with AND, sorted createdAt desc, id desc.
     * No pagination (MVP).
     */
    public List<Incident> list(@Nullable String q, @Nullable IncidentStatus status, @Nullable IncidentPriority priority) {
        StringBuilder jpql = new StringBuilder("select e from inc_Incident e where 1=1");
        Map<String, Object> params = new HashMap<>();
        if (q != null && !q.isBlank()) {
            jpql.append(" and (lower(e.id) like :q escape '\\' or lower(e.title) like :q escape '\\')");
            String pattern = "%" + q.trim().toLowerCase(Locale.ROOT)
                    .replace("\\", "\\\\")
                    .replace("%", "\\%")
                    .replace("_", "\\_") + "%";
            params.put("q", pattern);
        }
        if (status != null) {
            jpql.append(" and e.status = :status");
            params.put("status", status.getId());
        }
        if (priority != null) {
            jpql.append(" and e.priority = :priority");
            params.put("priority", priority.getId());
        }
        jpql.append(" order by e.createdAt desc, e.id desc");
        FluentLoader.ByQuery<Incident> loader = dataManager.load(Incident.class)
                .query(jpql.toString())
                .fetchPlan(fp -> fp.addFetchPlan(FetchPlan.BASE).add("assignee", FetchPlan.BASE));
        params.forEach(loader::parameter);
        return loader.list();
    }

    /**
     * Incident card with comments (createdAt asc, id asc) and history
     * (createdAt desc, id desc), loaded with separate focused queries.
     */
    public IncidentDetails get(String id) {
        Incident incident = dataManager.load(Incident.class).id(id)
                .fetchPlan(fp -> fp.addFetchPlan(FetchPlan.BASE).add("assignee", FetchPlan.BASE))
                .optional()
                .orElseThrow(() -> new NotFoundException("Инцидент не найден: " + id));
        List<Comment> comments = dataManager.load(Comment.class)
                .query("select e from inc_Comment e where e.incident.id = :incidentId order by e.createdAt asc, e.id asc")
                .parameter("incidentId", id)
                .fetchPlan(fp -> fp.addFetchPlan(FetchPlan.BASE).add("author", FetchPlan.BASE))
                .list();
        List<HistoryEvent> history = dataManager.load(HistoryEvent.class)
                .query("select e from inc_HistoryEvent e where e.incident.id = :incidentId order by e.createdAt desc, e.id desc")
                .parameter("incidentId", id)
                .fetchPlan(fp -> fp.addFetchPlan(FetchPlan.BASE).add("actor", FetchPlan.BASE))
                .list();
        return new IncidentDetails(incident, comments, history);
    }

    private Incident loadForChange(String id) {
        return dataManager.load(Incident.class).id(id)
                .fetchPlan(fp -> fp.addFetchPlan(FetchPlan.BASE).add("assignee", FetchPlan.BASE))
                .optional()
                .orElseThrow(() -> new NotFoundException("Инцидент не найден: " + id));
    }

    @Nullable
    private String validateTitle(@Nullable String raw, Map<String, String> errors) {
        if (raw == null || raw.trim().isEmpty()) {
            errors.put("title", "обязательное поле");
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.length() < 5 || trimmed.length() > 120) {
            errors.put("title", "длина 5–120 символов");
            return null;
        }
        return trimmed;
    }

    private void validateDescription(@Nullable String value, Map<String, String> errors) {
        if (value != null && value.length() > 2000) {
            errors.put("description", "длина не более 2000 символов");
        }
    }

    @Nullable
    private IncidentCategory validateCategory(@Nullable String id, Map<String, String> errors) {
        if (id == null) {
            errors.put("category", "обязательное поле");
            return null;
        }
        IncidentCategory value = IncidentCategory.fromId(id);
        if (value == null) {
            errors.put("category", "неизвестное значение");
        }
        return value;
    }

    @Nullable
    private IncidentPriority validatePriority(@Nullable String id, Map<String, String> errors) {
        if (id == null) {
            errors.put("priority", "обязательное поле");
            return null;
        }
        IncidentPriority value = IncidentPriority.fromId(id);
        if (value == null) {
            errors.put("priority", "неизвестное значение");
        }
        return value;
    }

    @Nullable
    private AppUser validateAssignee(@Nullable Integer assigneeId, Map<String, String> errors) {
        if (assigneeId == null) {
            return null;
        }
        AppUser user = appUserService.getById(assigneeId);
        if (user == null) {
            errors.put("assignee_id", "пользователь не найден");
        }
        return user;
    }

    private static OffsetDateTime nowUtc() {
        return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
