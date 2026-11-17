package com.company.incidentjmix.entity;

import io.jmix.core.metamodel.annotation.Composition;
import io.jmix.core.metamodel.annotation.InstanceName;
import io.jmix.core.metamodel.annotation.JmixEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * IT incident (TASK.md §1, ADR-001).
 * <p>
 * The id is ASSIGNED by IncidentService from the INC_ID_COUNTER row
 * (format INC-%03d, no upper bound). createdAt/updatedAt are server-managed
 * UTC timestamps set by the service layer. Business invariants (status
 * automaton, Closed immutability, field lengths) are enforced in services,
 * not by database constraints.
 */
@JmixEntity
@Entity(name = "inc_Incident")
@Table(name = "INC_INCIDENT", indexes = {
        @Index(name = "IDX_INC_INCIDENT_ON_STATUS", columnList = "STATUS"),
        @Index(name = "IDX_INC_INCIDENT_ON_PRIORITY", columnList = "PRIORITY"),
        @Index(name = "IDX_INC_INCIDENT_ON_CREATED_AT", columnList = "CREATED_AT")
})
public class Incident {

    @Column(name = "ID", nullable = false, length = 20)
    @Id
    private String id;

    @Version
    @Column(name = "VERSION", nullable = false)
    private Integer version;

    @InstanceName
    @Column(name = "TITLE", nullable = false, length = 120)
    private String title;

    @Column(name = "DESCRIPTION", length = 2000)
    private String description;

    @Column(name = "CATEGORY", nullable = false, length = 32)
    private String category;

    @Column(name = "PRIORITY", nullable = false, length = 32)
    private String priority;

    @Column(name = "STATUS", nullable = false, length = 32)
    private String status;

    @JoinColumn(name = "ASSIGNEE_ID")
    @ManyToOne(fetch = FetchType.LAZY)
    private AppUser assignee;

    @Column(name = "RESOLUTION", length = 2000)
    private String resolution;

    @Column(name = "CREATED_AT", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "UPDATED_AT", nullable = false)
    private OffsetDateTime updatedAt;

    @Composition
    @OneToMany(mappedBy = "incident")
    private List<Comment> comments;

    @Composition
    @OneToMany(mappedBy = "incident")
    private List<HistoryEvent> history;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public IncidentCategory getCategory() {
        return category == null ? null : IncidentCategory.fromId(category);
    }

    public void setCategory(IncidentCategory category) {
        this.category = category == null ? null : category.getId();
    }

    public IncidentPriority getPriority() {
        return priority == null ? null : IncidentPriority.fromId(priority);
    }

    public void setPriority(IncidentPriority priority) {
        this.priority = priority == null ? null : priority.getId();
    }

    public IncidentStatus getStatus() {
        return status == null ? null : IncidentStatus.fromId(status);
    }

    public void setStatus(IncidentStatus status) {
        this.status = status == null ? null : status.getId();
    }

    public AppUser getAssignee() {
        return assignee;
    }

    public void setAssignee(AppUser assignee) {
        this.assignee = assignee;
    }

    public String getResolution() {
        return resolution;
    }

    public void setResolution(String resolution) {
        this.resolution = resolution;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public List<Comment> getComments() {
        return comments;
    }

    public void setComments(List<Comment> comments) {
        this.comments = comments;
    }

    public List<HistoryEvent> getHistory() {
        return history;
    }

    public void setHistory(List<HistoryEvent> history) {
        this.history = history;
    }
}
