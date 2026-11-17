package com.company.incidentjmix.entity;

import io.jmix.core.DeletePolicy;
import io.jmix.core.entity.annotation.OnDeleteInverse;
import io.jmix.core.metamodel.annotation.InstanceName;
import io.jmix.core.metamodel.annotation.JmixEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.OffsetDateTime;

/**
 * Comment on an incident (TASK.md §1, ADR-001). Composition child of
 * {@link Incident}; incidents are never deleted in the MVP, so the FK has no
 * ON DELETE clause and @OnDeleteInverse is a Studio indicator only.
 */
@JmixEntity
@Entity(name = "inc_Comment")
@Table(name = "INC_COMMENT", indexes = {
        @Index(name = "IDX_INC_COMMENT_ON_INCIDENT_ID", columnList = "INCIDENT_ID")
})
public class Comment {

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

    @JoinColumn(name = "AUTHOR_ID", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private AppUser author;

    @InstanceName
    @Column(name = "TEXT", nullable = false, length = 2000)
    private String text;

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

    public AppUser getAuthor() {
        return author;
    }

    public void setAuthor(AppUser author) {
        this.author = author;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
