package com.company.incidentjmix.view.appuser;

import com.company.incidentjmix.entity.AppUser;
import com.company.incidentjmix.entity.UserRole;
import com.company.incidentjmix.view.main.MainView;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.data.renderer.Renderer;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.Route;
import io.jmix.core.Messages;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.model.CollectionLoader;
import io.jmix.flowui.view.MessageBundle;
import io.jmix.flowui.view.StandardListView;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.Supply;
import io.jmix.flowui.view.Target;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Domain users (TASK.md §4): read-only list, same source as the header
 * selector and GET /api/users. No create/edit/remove actions in the MVP.
 */
@Route(value = "users", layout = MainView.class)
@ViewController(id = "inc_AppUser.list")
@ViewDescriptor(path = "app-user-list-view.xml")
public class AppUserListView extends StandardListView<AppUser> {

    private static final Logger log = LoggerFactory.getLogger(AppUserListView.class);

    @Autowired
    private Messages messages;

    @Autowired
    private Notifications notifications;

    @ViewComponent
    private MessageBundle messageBundle;

    @ViewComponent
    private Span emptyState;

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        try {
            // dataLoadCoordinator auto-load happens inside super.beforeEnter
            super.beforeEnter(event);
        } catch (Exception e) {
            log.error("Failed to load application users", e);
            notifications.create(messageBundle.getMessage("appUsers.loadError") + " " + e.getMessage())
                    .withType(Notifications.Type.ERROR)
                    .show();
        }
    }

    @Subscribe(id = "appUsersDl", target = Target.DATA_LOADER)
    public void onAppUsersDlPostLoad(final CollectionLoader.PostLoadEvent<AppUser> event) {
        emptyState.setVisible(event.getLoadedEntities().isEmpty());
    }

    @Supply(to = "appUsersDataGrid.role", subject = "renderer")
    private Renderer<AppUser> roleRenderer() {
        return new ComponentRenderer<>(Span::new, (span, user) -> {
            UserRole role = user.getRole();
            span.setText(role != null ? messages.getMessage(role) : "");
        });
    }
}
