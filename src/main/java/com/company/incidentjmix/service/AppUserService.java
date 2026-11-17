package com.company.incidentjmix.service;

import com.company.incidentjmix.entity.AppUser;
import io.jmix.core.DataManager;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Read access to the preloaded domain users and resolution of the REST
 * X-User-Id header into a domain actor (ADR-003/005). Users are created only
 * by the Liquibase changelog; this service never mutates them.
 */
@Service
public class AppUserService {

    private final DataManager dataManager;

    public AppUserService(DataManager dataManager) {
        this.dataManager = dataManager;
    }

    /**
     * All users sorted by id (GET /api/users contract).
     */
    public List<AppUser> list() {
        return dataManager.load(AppUser.class)
                .query("select e from inc_AppUser e order by e.id asc")
                .list();
    }

    @Nullable
    public AppUser getById(Integer id) {
        return dataManager.load(AppUser.class).id(id).optional().orElse(null);
    }

    /**
     * Resolves the X-User-Id header value into a domain user.
     * Absent + required, non-numeric or unknown id → UnknownUserException (401).
     * Absent + optional → null (read-only REST requests).
     */
    @Nullable
    public AppUser resolveActor(@Nullable String headerValue, boolean required) {
        if (headerValue == null || headerValue.isBlank()) {
            if (required) {
                throw new UnknownUserException("Заголовок X-User-Id обязателен");
            }
            return null;
        }
        int id;
        try {
            id = Integer.parseInt(headerValue.trim());
        } catch (NumberFormatException e) {
            throw new UnknownUserException("Некорректный X-User-Id: " + headerValue);
        }
        AppUser user = getById(id);
        if (user == null) {
            throw new UnknownUserException("Неизвестный пользователь: " + headerValue);
        }
        return user;
    }
}
