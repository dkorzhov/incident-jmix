package com.company.incidentjmix.view.incident;

import com.company.incidentjmix.entity.AppUser;
import com.company.incidentjmix.entity.Comment;
import com.company.incidentjmix.entity.HistoryEvent;
import com.company.incidentjmix.entity.HistoryEventType;
import com.company.incidentjmix.entity.Incident;
import com.company.incidentjmix.entity.IncidentCategory;
import com.company.incidentjmix.entity.IncidentPriority;
import com.company.incidentjmix.entity.IncidentStatus;
import com.company.incidentjmix.service.AppUserService;
import com.company.incidentjmix.service.CommentService;
import com.company.incidentjmix.service.ConflictException;
import com.company.incidentjmix.service.CreateCommand;
import com.company.incidentjmix.service.IncidentDetails;
import com.company.incidentjmix.service.IncidentService;
import com.company.incidentjmix.service.NotFoundException;
import com.company.incidentjmix.service.PatchCommand;
import com.company.incidentjmix.service.ValidationException;
import com.company.incidentjmix.view.main.MainView;
import com.company.incidentjmix.view.support.CurrentAppUserProvider;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.data.renderer.Renderer;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.Route;
import io.jmix.core.EntityStates;
import io.jmix.core.Messages;
import io.jmix.flowui.Dialogs;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.app.inputdialog.DialogActions;
import io.jmix.flowui.app.inputdialog.DialogOutcome;
import io.jmix.flowui.app.inputdialog.InputParameter;
import io.jmix.flowui.component.combobox.JmixComboBox;
import io.jmix.flowui.component.tabsheet.JmixTabSheet;
import io.jmix.flowui.component.textarea.JmixTextArea;
import io.jmix.flowui.component.textfield.TypedTextField;
import io.jmix.flowui.kit.component.button.JmixButton;
import io.jmix.flowui.model.CollectionContainer;
import io.jmix.flowui.model.InstanceLoader;
import io.jmix.flowui.util.OperationResult;
import io.jmix.flowui.view.DialogMode;
import io.jmix.flowui.view.EditedEntityContainer;
import io.jmix.flowui.view.MessageBundle;
import io.jmix.flowui.view.StandardDetailView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.Supply;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Incident card and create form (TASK.md §4, DMI-19).
 *
 * <p>NFR-7: every mutation goes through the domain services
 * ({@link IncidentService}, {@link CommentService}) with the actor taken from
 * the header user selector ({@link CurrentAppUserProvider}). The standard
 * DataContext save is replaced in {@link BeforeSaveEvent}: the service result
 * is merged back and the standard commit is prevented, so all save paths
 * (save button, create dialog, unsaved-changes dialog) use the services.</p>
 *
 * <p>The UI only offers the status transitions allowed by the state machine
 * (New → In Progress → Resolved → Closed); the server remains the source of
 * truth and its 400/409 messages are shown as human-readable notifications.
 * A Closed incident is fully read-only.</p>
 */
@Route(value = "incidents/:id", layout = MainView.class)
@ViewController(id = "inc_Incident.detail")
@ViewDescriptor(path = "incident-detail-view.xml")
@EditedEntityContainer("incidentDc")
@DialogMode(width = "56rem")
public class IncidentDetailView extends StandardDetailView<Incident> {

    private static final Logger log = LoggerFactory.getLogger(IncidentDetailView.class);

    private static final DateTimeFormatter UTC_FORMAT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneOffset.UTC);

    private static final String ENTITY_MSG_GROUP = "com.company.incidentjmix.entity";

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private CommentService commentService;

    @Autowired
    private AppUserService appUserService;

    @Autowired
    private CurrentAppUserProvider currentAppUserProvider;

    @Autowired
    private Messages messages;

    @Autowired
    private Notifications notifications;

    @Autowired
    private Dialogs dialogs;

    @Autowired
    private EntityStates entityStates;

    @Autowired
    private ObjectMapper objectMapper;

    @ViewComponent
    private MessageBundle messageBundle;

    @ViewComponent
    private InstanceLoader<Incident> incidentDl;

    @ViewComponent
    private CollectionContainer<HistoryEvent> historyDc;

    @ViewComponent
    private TypedTextField<String> titleField;

    @ViewComponent
    private JmixComboBox<IncidentCategory> categoryField;

    @ViewComponent
    private JmixComboBox<IncidentPriority> priorityField;

    @ViewComponent
    private JmixTextArea commentField;

    @ViewComponent
    private JmixTabSheet cardTabs;

    @ViewComponent
    private HorizontalLayout cardHeader;

    @ViewComponent
    private HorizontalLayout actionsRow;

    @ViewComponent
    private HorizontalLayout timestampsRow;

    @ViewComponent
    private VerticalLayout commentsFeed;

    @ViewComponent
    private VerticalLayout commentForm;

    @ViewComponent
    private VerticalLayout resolutionBox;

    @ViewComponent
    private Span incidentNumber;

    @ViewComponent
    private H3 incidentTitle;

    @ViewComponent
    private Span priorityBadge;

    @ViewComponent
    private Span statusBadge;

    @ViewComponent
    private Span closedNotice;

    @ViewComponent
    private Span resolutionText;

    @ViewComponent
    private Span createdAtText;

    @ViewComponent
    private Span updatedAtText;

    @ViewComponent
    private Span historyEmpty;

    @ViewComponent
    private Span commentsEmpty;

    @ViewComponent
    private JmixButton takeInProgressButton;

    @ViewComponent
    private JmixButton resolveButton;

    @ViewComponent
    private JmixButton closeIncidentButton;

    @ViewComponent
    private JmixButton saveButton;

    @ViewComponent
    private JmixButton createButton;

    @ViewComponent
    private JmixButton cancelButton;

    /** Id assigned by the service on create; read by the registry after the dialog closes. */
    private String createdIncidentId;

    public String getCreatedIncidentId() {
        return createdIncidentId;
    }

    @Subscribe
    public void onInit(final InitEvent event) {
        // Layout containers have no classNames attribute in the descriptor
        // (Jmix 3.1); styling classes are applied here instead.
        getContent().addClassName("incident-detail-view");
        cardHeader.addClassName("incident-card-header");
        actionsRow.addClassName("card-actions");
        timestampsRow.addClassName("timestamps-row");

        setCrossFieldValidationEnabled(true);
        // The view shows its own success/error notifications.
        setShowSaveNotification(false);
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        try {
            // dataLoadCoordinator auto-load happens inside super.beforeEnter
            super.beforeEnter(event);
        } catch (Exception e) {
            log.error("Failed to open incident card", e);
            showError(messageBundle.getMessage("incident.loadError") + " " + e.getMessage());
        }
    }

    @Subscribe
    public void onBeforeShow(final BeforeShowEvent event) {
        Incident incident = getEditedEntity();
        if (entityStates.isNew(incident)) {
            setupCreateMode();
        } else {
            setupEditMode();
            loadCommentsAndHistory();
        }
    }

    @Override
    public String getPageTitle() {
        Incident incident = getEditedEntityOrNull();
        if (incident != null && incident.getId() != null && !entityStates.isNew(incident)) {
            return incident.getId() + " — " + incident.getTitle();
        }
        return messageBundle.getMessage("incidentCreate.title");
    }

    // --- Mode setup ---------------------------------------------------------

    private void setupCreateMode() {
        // A new incident has no history or comments yet: keep only the info tab.
        cardTabs.remove(2);
        cardTabs.remove(1);

        takeInProgressButton.setVisible(false);
        resolveButton.setVisible(false);
        closeIncidentButton.setVisible(false);
        saveButton.setVisible(false);
        createButton.setVisible(true);
        getViewActions().getAction("saveCloseAction").setText(messageBundle.getMessage("create.text"));

        incidentNumber.setVisible(false);
        priorityBadge.setVisible(false);
        statusBadge.setVisible(false);
        resolutionBox.setVisible(false);
        timestampsRow.setVisible(false);
        incidentTitle.setText(messageBundle.getMessage("incidentCreate.title"));
    }

    private void setupEditMode() {
        createButton.setVisible(false);
        updateHeader();
        updateActionButtons();
        updateClosedState();
    }

    private void updateHeader() {
        Incident incident = getEditedEntity();
        incidentNumber.setText(incident.getId());
        incidentTitle.setText(incident.getTitle());

        IncidentPriority priority = incident.getPriority();
        if (priority != null) {
            priorityBadge.setText(messages.getMessage(priority));
            priorityBadge.setClassName("badge badge-priority-" + priority.name().toLowerCase());
        } else {
            priorityBadge.setText("");
            priorityBadge.setClassName("badge");
        }

        IncidentStatus status = incident.getStatus();
        if (status != null) {
            statusBadge.setText(messages.getMessage(status));
            statusBadge.setClassName("badge badge-status-" + status.name().toLowerCase());
        } else {
            statusBadge.setText("");
            statusBadge.setClassName("badge");
        }

        String resolution = incident.getResolution();
        boolean hasResolution = resolution != null && !resolution.isBlank();
        resolutionBox.setVisible(hasResolution);
        if (hasResolution) {
            resolutionText.setText(resolution);
        }

        createdAtText.setText(messageBundle.getMessage("timestamps.created") + " " + formatUtc(incident.getCreatedAt()));
        updatedAtText.setText(messageBundle.getMessage("timestamps.updated") + " " + formatUtc(incident.getUpdatedAt()));
    }

    /** Only the transitions allowed by the state machine are offered (TASK.md §2). */
    private void updateActionButtons() {
        IncidentStatus status = getEditedEntity().getStatus();
        takeInProgressButton.setVisible(status == IncidentStatus.NEW);
        resolveButton.setVisible(status == IncidentStatus.IN_PROGRESS);
        closeIncidentButton.setVisible(status == IncidentStatus.RESOLVED);
        saveButton.setVisible(status != IncidentStatus.CLOSED);
    }

    private void updateClosedState() {
        boolean closed = getEditedEntity().getStatus() == IncidentStatus.CLOSED;
        setReadOnly(closed);
        closedNotice.setVisible(closed);
        commentForm.setVisible(!closed);
        // Leaving the card must stay possible even in read-only mode.
        cancelButton.setEnabled(true);
    }

    // --- Save through domain services (NFR-7) -------------------------------

    @Subscribe
    public void onValidation(final ValidationEvent event) {
        // Client-side affordance only; the service re-validates on the server.
        Incident incident = getEditedEntity();
        String title = incident.getTitle() == null ? "" : incident.getTitle().trim();
        if (!title.isEmpty() && (title.length() < 5 || title.length() > 120)) {
            event.getErrors().add(titleField, messageBundle.getMessage("validation.titleLength"));
        }
    }

    @Subscribe
    public void onBeforeSave(final BeforeSaveEvent event) {
        Incident incident = getEditedEntity();
        AppUser actor = currentAppUserProvider.getCurrentAppUser();
        if (actor == null) {
            showError(messageBundle.getMessage("error.noActor"));
            event.preventSave();
            return;
        }

        try {
            Incident saved;
            if (entityStates.isNew(incident)) {
                saved = incidentService.create(actor, new CreateCommand(
                        incident.getTitle(),
                        incident.getDescription(),
                        incident.getCategory() != null ? incident.getCategory().getId() : null,
                        incident.getPriority() != null ? incident.getPriority().getId() : null,
                        incident.getAssignee() != null ? incident.getAssignee().getId() : null));
                createdIncidentId = saved.getId();
            } else {
                PatchCommand patch = new PatchCommand()
                        .title(incident.getTitle())
                        .description(incident.getDescription())
                        .category(incident.getCategory() != null ? incident.getCategory().getId() : null)
                        .priority(incident.getPriority() != null ? incident.getPriority().getId() : null)
                        .assigneeId(incident.getAssignee() != null ? incident.getAssignee().getId() : null);
                saved = incidentService.update(actor, incident.getId(), patch);
            }

            event.getDataContext().merge(saved);
            if (entityStates.isNew(incident)) {
                clearChanges();
                notifications.create(messageBundle.getMessage("create.success"))
                        .withType(Notifications.Type.SUCCESS)
                        .show();
            } else {
                refreshCard();
                notifications.create(messageBundle.getMessage("save.success"))
                        .withType(Notifications.Type.SUCCESS)
                        .show();
            }
            // Persistence happened in the service: skip the standard DataContext commit.
            event.preventSave(OperationResult.success());
        } catch (ValidationException | ConflictException | NotFoundException e) {
            // Domain messages are already human-readable (field: reason / conflict text).
            showError(e.getMessage());
            event.preventSave();
        } catch (Exception e) {
            log.error("Failed to save incident", e);
            showError(messageBundle.getMessage("incident.saveError") + " " + e.getMessage());
            event.preventSave();
        }
    }

    /**
     * Saves field edits through the domain service (onBeforeSave) and keeps
     * the card open — unlike detail_saveClose, which would navigate away.
     */
    @Subscribe("saveButton")
    public void onSaveButtonClick(final ClickEvent<JmixButton> event) {
        save();
    }

    // --- Status transitions --------------------------------------------------

    @Subscribe("takeInProgressButton")
    public void onTakeInProgressButtonClick(final ClickEvent<JmixButton> event) {
        changeStatus(IncidentStatus.IN_PROGRESS, null);
    }

    @Subscribe("resolveButton")
    public void onResolveButtonClick(final ClickEvent<JmixButton> event) {
        dialogs.createInputDialog(this)
                .withHeader(messageBundle.getMessage("resolveDialog.header"))
                .withParameters(
                        InputParameter.stringParameter("resolution")
                                .withLabel(messageBundle.getMessage("resolveDialog.label"))
                                .withRequired(true)
                                .withRequiredMessage(messageBundle.getMessage("resolveDialog.required")))
                .withActions(DialogActions.OK_CANCEL)
                .withCloseListener(closeEvent -> {
                    if (closeEvent.closedWith(DialogOutcome.OK)) {
                        String resolution = closeEvent.getValue("resolution");
                        changeStatus(IncidentStatus.RESOLVED, resolution);
                    }
                })
                .open();
    }

    @Subscribe("closeIncidentButton")
    public void onCloseIncidentButtonClick(final ClickEvent<JmixButton> event) {
        changeStatus(IncidentStatus.CLOSED, null);
    }

    private void changeStatus(IncidentStatus target, String resolution) {
        AppUser actor = currentAppUserProvider.getCurrentAppUser();
        if (actor == null) {
            showError(messageBundle.getMessage("error.noActor"));
            return;
        }
        try {
            incidentService.changeStatus(actor, getEditedEntity().getId(), target, resolution);
            refreshCard();
            notifications.create(messageBundle.getMessage("statusChange.success"))
                    .withType(Notifications.Type.SUCCESS)
                    .show();
        } catch (ValidationException | ConflictException | NotFoundException e) {
            showError(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to change incident status", e);
            showError(messageBundle.getMessage("incident.saveError") + " " + e.getMessage());
        }
    }

    // --- Comments -------------------------------------------------------------

    @Subscribe("sendCommentButton")
    public void onSendCommentButtonClick(final ClickEvent<JmixButton> event) {
        String text = commentField.getValue();
        if (text == null || text.isBlank()) {
            notifications.create(messageBundle.getMessage("comment.empty"))
                    .withType(Notifications.Type.WARNING)
                    .show();
            return;
        }
        AppUser actor = currentAppUserProvider.getCurrentAppUser();
        if (actor == null) {
            showError(messageBundle.getMessage("error.noActor"));
            return;
        }
        try {
            commentService.add(actor, getEditedEntity().getId(), text);
            commentField.clear();
            refreshCard();
            notifications.create(messageBundle.getMessage("comment.success"))
                    .withType(Notifications.Type.SUCCESS)
                    .show();
        } catch (ValidationException | ConflictException | NotFoundException e) {
            showError(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to add comment", e);
            showError(messageBundle.getMessage("incident.saveError") + " " + e.getMessage());
        }
    }

    // --- Refresh ---------------------------------------------------------------

    /** Reloads the incident and its comments/history after a service mutation. */
    private void refreshCard() {
        incidentDl.load();
        clearChanges();
        updateHeader();
        updateActionButtons();
        updateClosedState();
        loadCommentsAndHistory();
    }

    private void loadCommentsAndHistory() {
        try {
            IncidentDetails details = incidentService.get(getEditedEntity().getId());
            historyDc.setItems(details.history());
            historyEmpty.setVisible(details.history().isEmpty());
            renderComments(details.comments());
        } catch (Exception e) {
            log.error("Failed to load incident comments/history", e);
            showError(messageBundle.getMessage("incident.loadError") + " " + e.getMessage());
        }
    }

    private void renderComments(List<Comment> comments) {
        commentsFeed.removeAll();
        commentsEmpty.setVisible(comments.isEmpty());
        for (Comment comment : comments) {
            VerticalLayout item = new VerticalLayout();
            item.addClassName("comment-item");
            item.setPadding(false);
            item.setSpacing(false);

            HorizontalLayout meta = new HorizontalLayout();
            meta.addClassName("comment-meta");
            meta.setPadding(false);
            Span author = new Span(comment.getAuthor() != null ? comment.getAuthor().getName() : "—");
            author.addClassName("comment-author");
            Span time = new Span(formatUtc(comment.getCreatedAt()));
            time.addClassName("comment-time");
            meta.add(author, time);

            Span text = new Span(comment.getText());
            text.addClassName("comment-text");

            item.add(meta, text);
            commentsFeed.add(item);
        }
    }

    // --- History renderers -------------------------------------------------------

    @Supply(to = "historyDataGrid.time", subject = "renderer")
    private Renderer<HistoryEvent> historyTimeRenderer() {
        return new ComponentRenderer<>(Span::new, (span, event) -> {
            span.setText(formatUtc(event.getCreatedAt()));
            span.addClassName("history-time");
        });
    }

    @Supply(to = "historyDataGrid.actor", subject = "renderer")
    private Renderer<HistoryEvent> historyActorRenderer() {
        return new ComponentRenderer<>(Span::new, (span, event) -> {
            span.setText(event.getActor() != null ? event.getActor().getName() : "—");
        });
    }

    @Supply(to = "historyDataGrid.type", subject = "renderer")
    private Renderer<HistoryEvent> historyTypeRenderer() {
        return new ComponentRenderer<>(Span::new, (span, event) -> {
            HistoryEventType type = event.getType();
            span.setText(type != null ? messages.getMessage(type) : "");
        });
    }

    @Supply(to = "historyDataGrid.details", subject = "renderer")
    private Renderer<HistoryEvent> historyDetailsRenderer() {
        return new ComponentRenderer<>(Span::new, (span, event) -> {
            span.setText(renderPayload(event));
            span.addClassName("history-details");
        });
    }

    /**
     * Renders the JSON payload (requirements §6) as human-readable
     * «Было: … / Стало: …» lines with localized enum labels and user names.
     */
    @SuppressWarnings("unchecked")
    private String renderPayload(HistoryEvent event) {
        String json = event.getPayload();
        if (json == null || json.isBlank()) {
            return "";
        }
        try {
            Map<String, Object> payload = objectMapper.readValue(json, Map.class);
            List<String> parts = new ArrayList<>();
            if (payload.get("before") instanceof Map<?, ?> before && !before.isEmpty()) {
                parts.add(messageBundle.getMessage("history.before") + ": " + renderFields(before));
            }
            if (payload.get("after") instanceof Map<?, ?> after && !after.isEmpty()) {
                parts.add(messageBundle.getMessage("history.after") + ": " + renderFields(after));
            }
            return String.join("\n", parts);
        } catch (Exception e) {
            log.warn("Failed to parse history payload of event {}", event.getId(), e);
            return messageBundle.getMessage("history.payloadError");
        }
    }

    private String renderFields(Map<?, ?> fields) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<?, ?> entry : fields.entrySet()) {
            String field = String.valueOf(entry.getKey());
            if ("comment_id".equals(field)) {
                continue; // technical id, the comment text is shown instead
            }
            parts.add(fieldLabel(field) + "=" + fieldValue(field, entry.getValue()));
        }
        return String.join(", ", parts);
    }

    private String fieldLabel(String field) {
        return switch (field) {
            case "assignee_id" -> messages.getMessage(ENTITY_MSG_GROUP, "Incident.assignee");
            case "text" -> messages.getMessage(ENTITY_MSG_GROUP, "Comment.text");
            default -> messages.getMessage(ENTITY_MSG_GROUP, "Incident." + field);
        };
    }

    private String fieldValue(String field, Object value) {
        if (value == null) {
            return "assignee_id".equals(field) ? messageBundle.getMessage("history.unassigned") : "—";
        }
        return switch (field) {
            case "category" -> {
                IncidentCategory category = IncidentCategory.fromId(String.valueOf(value));
                yield category != null ? messages.getMessage(category) : String.valueOf(value);
            }
            case "priority" -> {
                IncidentPriority priority = IncidentPriority.fromId(String.valueOf(value));
                yield priority != null ? messages.getMessage(priority) : String.valueOf(value);
            }
            case "status" -> {
                IncidentStatus status = IncidentStatus.fromId(String.valueOf(value));
                yield status != null ? messages.getMessage(status) : String.valueOf(value);
            }
            case "assignee_id" -> {
                Integer userId = value instanceof Number number ? number.intValue() : null;
                AppUser user = userId != null ? appUserService.getById(userId) : null;
                yield user != null ? user.getName() : String.valueOf(value);
            }
            default -> String.valueOf(value);
        };
    }

    // --- Helpers -------------------------------------------------------------

    private String formatUtc(OffsetDateTime dateTime) {
        return dateTime == null ? "—" : UTC_FORMAT.format(dateTime) + " (UTC)";
    }

    private void showError(String text) {
        notifications.create(text)
                .withType(Notifications.Type.ERROR)
                .show();
    }
}
