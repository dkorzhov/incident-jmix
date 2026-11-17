package com.company.incidentjmix.rest.dto;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * PATCH /api/incidents/{id}/status body (ADR-002). status is a raw enum id;
 * an unknown value reaches the service as null and becomes a 400 there.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record StatusChangeRequest(String status, String resolution) {
}
