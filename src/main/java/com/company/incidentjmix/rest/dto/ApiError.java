package com.company.incidentjmix.rest.dto;

/** Uniform REST error body (TASK.md §3, ADR-002): {"detail": "..."}. */
public record ApiError(String detail) {
}
