package com.company.incidentjmix.service;

import com.company.incidentjmix.entity.Comment;
import com.company.incidentjmix.entity.HistoryEvent;
import com.company.incidentjmix.entity.Incident;

import java.util.List;

/**
 * Incident card aggregate returned by IncidentService.get (ADR-005):
 * comments sorted createdAt asc, id asc; history sorted createdAt desc, id desc.
 * Loaded with separate focused queries instead of a multi-collection fetch graph.
 */
public record IncidentDetails(Incident incident, List<Comment> comments, List<HistoryEvent> history) {
}
