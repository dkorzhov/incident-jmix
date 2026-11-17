package com.company.incidentjmix.entity;

import io.jmix.core.metamodel.datatype.EnumClass;
import org.jspecify.annotations.Nullable;

/**
 * Incident lifecycle status. Stable ids equal the REST contract values (ADR-002);
 * note the space in the "In Progress" id.
 */
public enum IncidentStatus implements EnumClass<String> {

    NEW("New"),
    IN_PROGRESS("In Progress"),
    RESOLVED("Resolved"),
    CLOSED("Closed");

    private final String id;

    IncidentStatus(String id) {
        this.id = id;
    }

    @Override
    public String getId() {
        return id;
    }

    @Nullable
    public static IncidentStatus fromId(String id) {
        for (IncidentStatus value : values()) {
            if (value.getId().equals(id)) {
                return value;
            }
        }
        return null;
    }
}
