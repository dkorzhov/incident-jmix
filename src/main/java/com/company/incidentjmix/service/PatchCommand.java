package com.company.incidentjmix.service;

import org.jspecify.annotations.Nullable;

/**
 * Command for IncidentService.update (ADR-005). Distinguishes an ABSENT field
 * (leave unchanged) from an explicitly passed null (clear description /
 * unassign assignee): each fluent setter marks the field present.
 * REST/UI layers build the command from the request payload.
 */
public class PatchCommand {

    private boolean titlePresent;
    private String title;

    private boolean descriptionPresent;
    private String description;

    private boolean categoryPresent;
    private String category;

    private boolean priorityPresent;
    private String priority;

    private boolean assigneeIdPresent;
    private Integer assigneeId;

    public PatchCommand title(@Nullable String title) {
        this.titlePresent = true;
        this.title = title;
        return this;
    }

    public PatchCommand description(@Nullable String description) {
        this.descriptionPresent = true;
        this.description = description;
        return this;
    }

    public PatchCommand category(@Nullable String category) {
        this.categoryPresent = true;
        this.category = category;
        return this;
    }

    public PatchCommand priority(@Nullable String priority) {
        this.priorityPresent = true;
        this.priority = priority;
        return this;
    }

    public PatchCommand assigneeId(@Nullable Integer assigneeId) {
        this.assigneeIdPresent = true;
        this.assigneeId = assigneeId;
        return this;
    }

    public boolean isTitlePresent() {
        return titlePresent;
    }

    @Nullable
    public String getTitle() {
        return title;
    }

    public boolean isDescriptionPresent() {
        return descriptionPresent;
    }

    @Nullable
    public String getDescription() {
        return description;
    }

    public boolean isCategoryPresent() {
        return categoryPresent;
    }

    @Nullable
    public String getCategory() {
        return category;
    }

    public boolean isPriorityPresent() {
        return priorityPresent;
    }

    @Nullable
    public String getPriority() {
        return priority;
    }

    public boolean isAssigneeIdPresent() {
        return assigneeIdPresent;
    }

    @Nullable
    public Integer getAssigneeId() {
        return assigneeId;
    }
}
