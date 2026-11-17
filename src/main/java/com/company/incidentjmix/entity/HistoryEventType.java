package com.company.incidentjmix.entity;

import io.jmix.core.metamodel.datatype.EnumClass;
import org.jspecify.annotations.Nullable;

/**
 * History event type (TASK.md §1). Stable ids equal the REST contract values.
 */
public enum HistoryEventType implements EnumClass<String> {

    CREATED("created"),
    ASSIGNED("assigned"),
    STATUS_CHANGED("status_changed"),
    PRIORITY_CHANGED("priority_changed"),
    COMMENTED("commented"),
    RESOLVED("resolved"),
    FIELDS_UPDATED("fields_updated");

    private final String id;

    HistoryEventType(String id) {
        this.id = id;
    }

    @Override
    public String getId() {
        return id;
    }

    @Nullable
    public static HistoryEventType fromId(String id) {
        for (HistoryEventType value : values()) {
            if (value.getId().equals(id)) {
                return value;
            }
        }
        return null;
    }
}
