package com.company.incidentjmix.rest;

import com.company.incidentjmix.entity.AppUser;
import com.company.incidentjmix.rest.dto.UserDto;
import com.company.incidentjmix.service.AppUserService;
import io.jmix.core.security.SystemAuthenticator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** GET /api/users (TASK.md §3, ADR-002): preloaded users sorted by id. */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final AppUserService appUserService;
    private final SystemAuthenticator systemAuthenticator;
    private final RestDtoMapper mapper;

    public UserController(AppUserService appUserService,
                          SystemAuthenticator systemAuthenticator,
                          RestDtoMapper mapper) {
        this.appUserService = appUserService;
        this.systemAuthenticator = systemAuthenticator;
        this.mapper = mapper;
    }

    @GetMapping
    public List<UserDto> list(@CurrentAppUser(optional = true) AppUser actor) {
        // actor is resolved only to validate a present X-User-Id header
        return systemAuthenticator.withSystem(appUserService::list).stream()
                .map(mapper::toUserDto)
                .toList();
    }
}
