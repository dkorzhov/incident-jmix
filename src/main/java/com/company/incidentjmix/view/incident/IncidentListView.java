package com.company.incidentjmix.view.incident;

import com.company.incidentjmix.entity.Incident;
import com.company.incidentjmix.entity.IncidentPriority;
import com.company.incidentjmix.entity.IncidentStatus;
import com.company.incidentjmix.service.DashboardData;
import com.company.incidentjmix.service.DashboardService;
import com.company.incidentjmix.view.main.MainView;
import com.company.incidentjmix.view.support.KpiCards;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.data.renderer.Renderer;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.Route;
import io.jmix.core.Messages;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.component.combobox.JmixComboBox;
import io.jmix.flowui.component.textfield.TypedTextField;
import io.jmix.flowui.model.CollectionLoader;
import io.jmix.flowui.view.View.InitEvent;
import io.jmix.flowui.view.MessageBundle;
import io.jmix.flowui.view.View.ReadyEvent;
import io.jmix.flowui.view.StandardListView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.Supply;
import io.jmix.flowui.view.Target;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;

/**
 * Incident registry (TASK.md §4, ADR-004): KPI cards, search by ID/title,
 * status/priority filters, badge table sorted by creation time desc.
 * Read-only in DMI-18: the detail view and the create button are DMI-19.
 */
@Route(value = "incidents", layout = MainView.class)
@ViewController(id = "inc_Incident.list")
@ViewDescriptor(path = "incident-list-view.xml")
public class IncidentListView extends StandardListView<Incident> {

    private static final Logger log = LoggerFactory.getLogger(IncidentListView.class);

    private static final String BASE_QUERY = "select e from inc_Incident e";
    private static final String ORDER_BY = " order by e.createdAt desc, e.id desc";

    @Autowired
    private DashboardService dashboardService;

    @Autowired
    private Messages messages;

    @Autowired
    private Notifications notifications;

    @ViewComponent
    private MessageBundle messageBundle;

    @ViewComponent
    private CollectionLoader<Incident> incidentsDl;

    @ViewComponent
    private TypedTextField<String> searchField;

    @ViewComponent
    private JmixComboBox<IncidentStatus> statusFilter;

    @ViewComponent
    private JmixComboBox<IncidentPriority> priorityFilter;

    @ViewComponent
    private HorizontalLayout kpiRow;

    @ViewComponent
    private HorizontalLayout filtersRow;

    @ViewComponent
    private Span emptyState;

    @Subscribe
    public void onInit(final InitEvent event) {
        // Layout containers have no classNames attribute in the descriptor
        // (Jmix 3.1); styling classes are applied here instead.
        getContent().addClassName("incident-list-view");
        kpiRow.addClassName("kpi-row");
        filtersRow.addClassName("filters-row");

        statusFilter.setItems(IncidentStatus.values());
        statusFilter.setItemLabelGenerator(messages::getMessage);
        statusFilter.addValueChangeListener(e -> applyFilters());

        priorityFilter.setItems(IncidentPriority.values());
        priorityFilter.setItemLabelGenerator(messages::getMessage);
        priorityFilter.addValueChangeListener(e -> applyFilters());

        searchField.addValueChangeListener(e -> applyFilters());
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        try {
            // dataLoadCoordinator auto-load happens inside super.beforeEnter
            super.beforeEnter(event);
        } catch (Exception e) {
            log.error("Failed to load incidents", e);
            notifications.create(messageBundle.getMessage("incidents.loadError") + " " + e.getMessage())
                    .withType(Notifications.Type.ERROR)
                    .show();
        }
    }

    @Subscribe
    public void onReady(final ReadyEvent event) {
        loadKpi();
    }

    private void loadKpi() {
        DashboardData.Counts counts;
        try {
            counts = dashboardService.getDashboard().counts();
        } catch (Exception e) {
            log.error("Failed to load dashboard counts", e);
            notifications.create(messageBundle.getMessage("incidents.loadError") + " " + e.getMessage())
                    .withType(Notifications.Type.ERROR)
                    .show();
            return;
        }
        kpiRow.removeAll();
        kpiRow.add(
                KpiCards.createCard(String.valueOf(counts.open()), messageBundle.getMessage("kpi.open"), "blue"),
                KpiCards.createCard(String.valueOf(counts.newCount()), messageBundle.getMessage("kpi.new"), "orange"),
                KpiCards.createCard(String.valueOf(counts.inProgress()), messageBundle.getMessage("kpi.inProgress"), "neutral"),
                KpiCards.createCard(String.valueOf(counts.critical()), messageBundle.getMessage("kpi.critical"), "red"),
                KpiCards.createCard(String.valueOf(counts.resolvedToday()), messageBundle.getMessage("kpi.resolvedToday"), "green"));
    }

    /**
     * Rebuilds the loader query from the search text and the two filters.
     * Empty filter value means "all". Search is case-insensitive over id/title.
     */
    private void applyFilters() {
        List<String> conditions = new ArrayList<>();

        String search = searchField.getValue();
        if (search != null && !search.isBlank()) {
            conditions.add("(lower(e.id) like :search or lower(e.title) like :search)");
            incidentsDl.setParameter("search", "%" + search.trim().toLowerCase() + "%");
        } else {
            incidentsDl.removeParameter("search");
        }

        IncidentStatus status = statusFilter.getValue();
        if (status != null) {
            conditions.add("e.status = :status");
            incidentsDl.setParameter("status", status.getId());
        } else {
            incidentsDl.removeParameter("status");
        }

        IncidentPriority priority = priorityFilter.getValue();
        if (priority != null) {
            conditions.add("e.priority = :priority");
            incidentsDl.setParameter("priority", priority.getId());
        } else {
            incidentsDl.removeParameter("priority");
        }

        String query = conditions.isEmpty()
                ? BASE_QUERY + ORDER_BY
                : BASE_QUERY + " where " + String.join(" and ", conditions) + ORDER_BY;
        incidentsDl.setQuery(query);

        try {
            incidentsDl.load();
        } catch (Exception e) {
            log.error("Failed to load incidents", e);
            notifications.create(messageBundle.getMessage("incidents.loadError") + " " + e.getMessage())
                    .withType(Notifications.Type.ERROR)
                    .show();
        }
    }

    @Subscribe(id = "incidentsDl", target = Target.DATA_LOADER)
    public void onIncidentsDlPostLoad(final CollectionLoader.PostLoadEvent<Incident> event) {
        emptyState.setVisible(event.getLoadedEntities().isEmpty());
    }

    @Supply(to = "incidentsDataGrid.priority", subject = "renderer")
    private Renderer<Incident> priorityRenderer() {
        return new ComponentRenderer<>(Span::new, (span, incident) -> {
            IncidentPriority priority = incident.getPriority();
            if (priority != null) {
                span.setText(messages.getMessage(priority));
                span.setClassName("badge badge-priority-" + priority.name().toLowerCase());
            } else {
                span.setText("");
                span.setClassName("badge");
            }
        });
    }

    @Supply(to = "incidentsDataGrid.status", subject = "renderer")
    private Renderer<Incident> statusRenderer() {
        return new ComponentRenderer<>(Span::new, (span, incident) -> {
            IncidentStatus status = incident.getStatus();
            if (status != null) {
                span.setText(messages.getMessage(status));
                span.setClassName("badge badge-status-" + status.name().toLowerCase());
            } else {
                span.setText("");
                span.setClassName("badge");
            }
        });
    }
}
