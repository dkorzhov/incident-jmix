package com.company.incidentjmix.entity;

import io.jmix.core.DeletePolicy;
import io.jmix.core.entity.annotation.OnDeleteInverse;
import io.jmix.core.metamodel.annotation.JmixEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.OffsetDateTime;

/**
 * Automatically recorded history event (TASK.md §1, ADR-001/005).
 * Written only by HistoryService inside the mutating service transaction;
 * immutable afterwards (no public mutate/delete API). payload is a JSON
 * string with before/after values, stored as clob (HSQLDB-compatible).
 */
@JmixEntity
@Entity(name = "inc_HistoryEvent")
@Table(name = "INC_HISTORY_EVENT", indexes = {
        @Index(name = "IDX_INC_HISTORY_ON_INCIDENT_CREATED", columnList = "INCIDENT_ID, CREATED_AT"),
        @Index(name = "IDX_INC_HISTORY_ON_CREATED_AT", columnList = "CREATED_AT")
})
public class HistoryEvent {

    @Column(name = "ID", nullable = false)
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Version
    @Column(name = "VERSION", nullable = false)
    private Integer version;

    @OnDeleteInverse(DeletePolicy.CASCADE)
    @JoinColumn(name = "INCIDENT_ID", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Incident incident;

    @JoinColumn(name = "ACTOR_ID", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private AppUser actor;

    @Column(name = "TYPE", nullable = false, length = 32)
    private String type;

    @Lob
    @Column(name = "PAYLOAD")
    private String payload;

    @Column(name = "CREATED_AT", nullable = false)
    private OffsetDateTime createdAt;

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

    public Incident getIncident() {
        return incident;
    }

    public void setIncident(Incident incident) {
        this.incident = incident;
    }

    public AppUser getActor() {
        return actor;
    }

    public void setActor(AppUser actor) {
        this.actor = actor;
    }

    public HistoryEventType getType() {
        return type == null ? null : HistoryEventType.fromId(type);
    }

    public void setType(HistoryEventType type) {
        this.type = type == null ? null : type.getId();
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
