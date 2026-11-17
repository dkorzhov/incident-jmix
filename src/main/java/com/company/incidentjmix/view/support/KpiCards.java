package com.company.incidentjmix.view.support;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;

/**
 * Builds the KPI cards shared by the dashboard and the incident registry
 * (docs/ui-concept.png). Styling lives in the incident-jmix-aura theme
 * (kpi-row / kpi-card / kpi-card-* classes), values are plain text.
 */
public final class KpiCards {

    private KpiCards() {
    }

    /**
     * @param color one of: blue, orange, neutral, red, green
     */
    public static Div createCard(String value, String label, String color) {
        Div card = new Div();
        card.addClassNames("kpi-card", "kpi-card-" + color);

        Span valueSpan = new Span(value);
        valueSpan.addClassName("kpi-card-value");

        Span labelSpan = new Span(label);
        labelSpan.addClassName("kpi-card-label");

        card.add(valueSpan, labelSpan);
        return card;
    }
}
