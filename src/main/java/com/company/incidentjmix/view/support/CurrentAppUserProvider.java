package com.company.incidentjmix.view.support;

import com.company.incidentjmix.entity.AppUser;
import com.company.incidentjmix.service.AppUserService;
import io.jmix.core.session.SessionData;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * ADR-003: the domain actor ("current user") of the MVP is an AppUser chosen
 * in the MainView header selector, not the authenticated technical account.
 * The selection is stored in the Vaadin session; the actor itself is always
 * reloaded fresh from the database so services receive a managed entity.
 */
@Component
public class CurrentAppUserProvider {

    public static final String SESSION_ATTRIBUTE = "currentAppUserId";

    private final ObjectProvider<SessionData> sessionDataProvider;
    private final AppUserService appUserService;

    public CurrentAppUserProvider(ObjectProvider<SessionData> sessionDataProvider,
                                  AppUserService appUserService) {
        this.sessionDataProvider = sessionDataProvider;
        this.appUserService = appUserService;
    }

    /**
     * Returns the selected actor, freshly loaded. Falls back to the first
     * user by id when nothing is selected or the stored id no longer exists.
     * Returns null only when the user list is empty.
     */
    public AppUser getCurrentAppUser() {
        Integer id = getCurrentAppUserId();
        if (id != null) {
            AppUser user = appUserService.getById(id);
            if (user != null) {
                return user;
            }
        }
        List<AppUser> users = appUserService.list();
        return users.isEmpty() ? null : users.get(0);
    }

    public Integer getCurrentAppUserId() {
        Object value = sessionDataProvider.getObject().getAttribute(SESSION_ATTRIBUTE);
        return value instanceof Integer integer ? integer : null;
    }

    public void setCurrentAppUserId(Integer id) {
        sessionDataProvider.getObject().setAttribute(SESSION_ATTRIBUTE, id);
    }
}
