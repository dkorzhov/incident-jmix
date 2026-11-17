package com.company.incidentjmix.rest;

import com.company.incidentjmix.entity.AppUser;
import com.company.incidentjmix.rest.dto.DashboardDto;
import com.company.incidentjmix.service.DashboardService;
import io.jmix.core.security.SystemAuthenticator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** GET /api/dashboard (TASK.md §3, ADR-002): counts, by_priority, recent_activity, dynamics. */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;
    private final SystemAuthenticator systemAuthenticator;
    private final RestDtoMapper mapper;

    public DashboardController(DashboardService dashboardService,
                               SystemAuthenticator systemAuthenticator,
                               RestDtoMapper mapper) {
        this.dashboardService = dashboardService;
        this.systemAuthenticator = systemAuthenticator;
        this.mapper = mapper;
    }

    @GetMapping
    public DashboardDto dashboard(@CurrentAppUser(optional = true) AppUser actor) {
        // actor is resolved only to validate a present X-User-Id header
        return mapper.toDashboardDto(systemAuthenticator.withSystem(dashboardService::getDashboard));
    }
}
