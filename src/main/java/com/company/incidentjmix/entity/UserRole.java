package com.company.incidentjmix.entity;

import io.jmix.core.metamodel.datatype.EnumClass;
import org.jspecify.annotations.Nullable;

/**
 * Domain role of an application user (TASK.md §1). Gives no security permissions;
 * the technical security model lives in {@link User}.
 */
public enum UserRole implements EnumClass<String> {

    ENGINEER("engineer"),
    MANAGER("manager");

    private final String id;

    UserRole(String id) {
        this.id = id;
    }

    @Override
    public String getId() {
        return id;
    }

    @Nullable
    public static UserRole fromId(String id) {
        for (UserRole value : values()) {
            if (value.getId().equals(id)) {
                return value;
            }
        }
        return null;
    }
}
