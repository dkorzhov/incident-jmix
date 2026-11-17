package com.company.incidentjmix.entity;

import io.jmix.core.metamodel.annotation.InstanceName;
import io.jmix.core.metamodel.annotation.JmixEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Domain user of the incident tracker (TASK.md §1, ADR-001).
 * <p>
 * The id is ASSIGNED: users are preloaded by the Liquibase changelog
 * (020-init-app-user.xml), never generated at runtime. This entity carries no
 * security permissions — the technical security user is {@link User}.
 */
@JmixEntity
@Entity(name = "inc_AppUser")
@Table(name = "INC_APP_USER")
public class AppUser {

    @Column(name = "ID", nullable = false)
    @Id
    private Integer id;

    @Version
    @Column(name = "VERSION", nullable = false)
    private Integer version;

    @InstanceName
    @Column(name = "NAME", nullable = false)
    private String name;

    @Column(name = "ROLE", nullable = false, length = 32)
    private String role;

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public UserRole getRole() {
        return role == null ? null : UserRole.fromId(role);
    }

    public void setRole(UserRole role) {
        this.role = role == null ? null : role.getId();
    }
}
