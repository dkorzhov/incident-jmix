package com.company.incidentjmix.entity;

import io.jmix.core.metamodel.datatype.EnumClass;
import org.jspecify.annotations.Nullable;

/**
 * Incident priority. Stable ids equal the REST contract values (ADR-002).
 * Declaration order is the fixed dashboard by_priority order (ADR-005).
 */
public enum IncidentPriority implements EnumClass<String> {

    CRITICAL("Critical"),
    HIGH("High"),
    MEDIUM("Medium"),
    LOW("Low");

    private final String id;

    IncidentPriority(String id) {
        this.id = id;
    }

    @Override
    public String getId() {
        return id;
    }

    @Nullable
    public static IncidentPriority fromId(String id) {
        for (IncidentPriority value : values()) {
            if (value.getId().equals(id)) {
                return value;
            }
        }
        return null;
    }
}
