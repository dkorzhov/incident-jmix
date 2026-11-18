package com.company.incidentjmix.view.dashboard;

import com.company.incidentjmix.entity.HistoryEventType;
import com.company.incidentjmix.entity.IncidentPriority;
import com.company.incidentjmix.service.DashboardData;
import com.company.incidentjmix.service.DashboardService;
import com.company.incidentjmix.view.main.MainView;
import com.company.incidentjmix.view.support.KpiCards;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.Route;
import io.jmix.chartsflowui.component.Chart;
import io.jmix.chartsflowui.data.item.MapDataItem;
import io.jmix.chartsflowui.kit.component.model.DataSet;
import io.jmix.chartsflowui.kit.component.model.shared.Color;
import io.jmix.chartsflowui.kit.data.chart.ListChartItems;
import io.jmix.core.Messages;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.view.View.InitEvent;
import io.jmix.flowui.view.MessageBundle;
import io.jmix.flowui.view.View.ReadyEvent;
import io.jmix.flowui.view.StandardView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Dashboard (TASK.md §4, ADR-004/005): KPI cards, by-priority pie and
 * 7-day dynamics line (Charts add-on), recent activity. All numbers come
 * from DashboardService; an empty database renders zero states, not errors.
 */
@Route(value = "dashboard", layout = MainView.class)
@ViewController(id = "inc_DashboardView")
@ViewDescriptor(path = "dashboard-view.xml")
public class DashboardView extends StandardView {

    private static final Logger log = LoggerFactory.getLogger(DashboardView.class);

    private static final DateTimeFormatter ACTIVITY_TIME_FORMAT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("dd.MM");

    @Autowired
    private DashboardService dashboardService;

    @Autowired
    private Notifications notifications;

    @Autowired
    private Messages messages;

    @ViewComponent
    private MessageBundle messageBundle;

    @ViewComponent
    private HorizontalLayout kpiRow;

    @ViewComponent
    private HorizontalLayout chartsRow;

    @ViewComponent
    private VerticalLayout priorityChartBox;

    @ViewComponent
    private VerticalLayout dynamicsChartBox;

    @ViewComponent
    private VerticalLayout activityBox;

    @ViewComponent
    private Chart priorityChart;

    @ViewComponent
    private Span priorityChartEmpty;

    @ViewComponent
    private Chart dynamicsChart;

    @ViewComponent
    private VerticalLayout activityList;

    @ViewComponent
    private Span activityEmpty;

    @Subscribe
    public void onInit(final InitEvent event) {
        // Layout containers have no classNames attribute in the descriptor
        // (Jmix 3.1); styling classes are applied here instead.
        getContent().addClassName("dashboard-view");
        kpiRow.addClassName("kpi-row");
        chartsRow.addClassName("dashboard-charts-row");
        priorityChartBox.addClassName("dashboard-panel");
        dynamicsChartBox.addClassName("dashboard-panel");
        activityBox.addClassName("dashboard-panel");
        activityList.addClassName("activity-list");
    }

    @Subscribe
    public void onReady(final ReadyEvent event) {
        loadDashboard();
    }

    private void loadDashboard() {
        DashboardData data;
        try {
            data = dashboardService.getDashboard();
        } catch (Exception e) {
            log.error("Failed to load dashboard data", e);
            notifications.create(messageBundle.getMessage("dashboard.loadError") + " " + e.getMessage())
                    .withType(Notifications.Type.ERROR)
                    .show();
            showEmptyStates();
            return;
        }

        renderKpi(data.counts());
        renderPriorityChart(data.byPriority());
        renderDynamicsChart(data.dynamics());
        renderActivity(data.recentActivity());
    }

    private void renderKpi(DashboardData.Counts counts) {
        kpiRow.removeAll();
        kpiRow.add(
                KpiCards.createCard(String.valueOf(counts.open()), messageBundle.getMessage("kpi.open"), "blue"),
                KpiCards.createCard(String.valueOf(counts.newCount()), messageBundle.getMessage("kpi.new"), "orange"),
                KpiCards.createCard(String.valueOf(counts.inProgress()), messageBundle.getMessage("kpi.inProgress"), "neutral"),
                KpiCards.createCard(String.valueOf(counts.critical()), messageBundle.getMessage("kpi.critical"), "red"),
                KpiCards.createCard(String.valueOf(counts.resolvedToday()), messageBundle.getMessage("kpi.resolvedToday"), "green"));
    }

    private void renderPriorityChart(List<DashboardData.PriorityCount> byPriority) {
        Map<String, Long> counts = new HashMap<>();
        for (DashboardData.PriorityCount item : byPriority) {
            counts.put(item.priority(), item.count());
        }

        // The service returns non-zero entries only; the UI shows all four
        // categories so the legend is complete (ADR-005). Literal colors:
        // ECharts canvas does not resolve CSS custom properties.
        List<MapDataItem> items = new ArrayList<>();
        long total = 0;
        for (IncidentPriority priority : IncidentPriority.values()) {
            long count = counts.getOrDefault(priority.getId(), 0L);
            total += count;
            items.add(new MapDataItem(Map.of(
                    "priority", messages.getMessage(priority),
                    "count", count)));
        }

        if (total == 0) {
            // Remove, do not hide: a hidden jmix-chart is still attached to the DOM
            // without $server, and its connectedCallback unconditionally calls
            // $server.ready() after 200ms -> TypeError. A component removed before
            // the first render is never attached on the client.
            priorityChartBox.remove(priorityChart);
            priorityChartEmpty.setVisible(true);
            return;
        }

        priorityChartEmpty.setVisible(false);
        priorityChart.setColorPalette(
                new Color("#ef4444"),
                new Color("#f97316"),
                new Color("#eab308"),
                new Color("#3b82f6"));
        priorityChart.setDataSet(new DataSet()
                .withSource(new DataSet.Source<MapDataItem>()
                        .withDataProvider(new ListChartItems<>(items))
                        .withCategoryField("priority")
                        .withValueField("count")));
    }

    private void renderDynamicsChart(List<DashboardData.DayDynamics> dynamics) {
        List<MapDataItem> items = new ArrayList<>();
        for (DashboardData.DayDynamics day : dynamics) {
            items.add(new MapDataItem(Map.of(
                    "day", DAY_FORMAT.format(day.date()),
                    "created", day.created(),
                    "resolved", day.resolved())));
        }
        dynamicsChart.setDataSet(new DataSet()
                .withSource(new DataSet.Source<MapDataItem>()
                        .withDataProvider(new ListChartItems<>(items))
                        .withCategoryField("day")
                        .withValueFields("created", "resolved")));
    }

    private void renderActivity(List<DashboardData.ActivityItem> activity) {
        activityList.removeAll();
        if (activity.isEmpty()) {
            activityEmpty.setVisible(true);
            return;
        }
        activityEmpty.setVisible(false);

        for (DashboardData.ActivityItem item : activity) {
            Div row = new Div();
            row.addClassName("activity-item");

            Span incident = new Span(item.incidentId() + " — " + item.incidentTitle());
            incident.addClassName("activity-incident");

            Span type = new Span(eventTypeCaption(item.type()));
            type.addClassName("activity-type");

            Span actor = new Span(item.actorName());
            actor.addClassName("activity-actor");

            Span time = new Span(ACTIVITY_TIME_FORMAT.format(item.createdAt()) + " (UTC)");
            time.addClassName("activity-time");

            row.add(incident, type, actor, time);
            activityList.add(row);
        }
    }

    private String eventTypeCaption(String typeId) {
        HistoryEventType type = HistoryEventType.fromId(typeId);
        return type != null ? messages.getMessage(type) : typeId;
    }

    private void showEmptyStates() {
        kpiRow.removeAll();
        priorityChartBox.remove(priorityChart);
        priorityChartEmpty.setVisible(true);
        activityList.removeAll();
        activityEmpty.setVisible(true);
    }
}
