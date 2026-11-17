package com.company.incidentjmix.rest.dto;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** GET /api/users item (ADR-002): id, name, role. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record UserDto(Integer id, String name, String role) {
}
