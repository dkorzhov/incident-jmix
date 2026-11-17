package com.company.incidentjmix.entity;

import io.jmix.core.metamodel.datatype.EnumClass;
import org.jspecify.annotations.Nullable;

/**
 * Incident category. Stable ids equal the REST contract values (ADR-002).
 */
public enum IncidentCategory implements EnumClass<String> {

    NETWORK("Network"),
    MAIL("Mail"),
    PORTAL("Portal"),
    DATABASE("Database"),
    WORKPLACE("Workplace"),
    OTHER("Other");

    private final String id;

    IncidentCategory(String id) {
        this.id = id;
    }

    @Override
    public String getId() {
        return id;
    }

    @Nullable
    public static IncidentCategory fromId(String id) {
        for (IncidentCategory value : values()) {
            if (value.getId().equals(id)) {
                return value;
            }
        }
        return null;
    }
}
