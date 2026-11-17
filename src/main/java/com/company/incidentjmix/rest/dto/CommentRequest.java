package com.company.incidentjmix.rest.dto;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * POST /api/incidents/{id}/comments body (ADR-002). The author is always the
 * X-User-Id header user; any author field in the body is ignored.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CommentRequest(String text) {
}
