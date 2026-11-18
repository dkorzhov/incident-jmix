package com.company.incidentjmix.view.main;

import com.company.incidentjmix.entity.AppUser;
import com.company.incidentjmix.service.AppUserService;
import com.company.incidentjmix.view.support.CurrentAppUserProvider;
import com.vaadin.flow.component.HasValue;
import com.vaadin.flow.router.Route;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.app.main.StandardMainView;
import io.jmix.flowui.component.combobox.JmixComboBox;
import io.jmix.flowui.view.MessageBundle;
import io.jmix.flowui.view.View.InitEvent;
import io.jmix.flowui.view.Subscribe;
import io.jmix.flowui.view.ViewComponent;
import io.jmix.flowui.view.ViewController;
import io.jmix.flowui.view.ViewDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

@Route("")
@ViewController(id = "inc_MainView")
@ViewDescriptor(path = "main-view.xml")
public class MainView extends StandardMainView {

    private static final Logger log = LoggerFactory.getLogger(MainView.class);

    @Autowired
    private AppUserService appUserService;

    @Autowired
    private CurrentAppUserProvider currentAppUserProvider;

    @Autowired
    private Notifications notifications;

    @ViewComponent
    private JmixComboBox<AppUser> userSelector;

    @ViewComponent
    private MessageBundle messageBundle;

    @Subscribe
    public void onInit(final InitEvent event) {
        initUserSelector();
    }

    /**
     * ADR-003: header selector of the domain actor. Same source as
     * GET /api/users (AppUserService.list()); the choice is kept in the
     * Vaadin session, default is the first user by id.
     */
    private void initUserSelector() {
        List<AppUser> users;
        try {
            users = appUserService.list();
        } catch (Exception e) {
            log.error("Failed to load application users", e);
            notifications.create(messageBundle.getMessage("users.loadError") + " " + e.getMessage())
                    .withType(Notifications.Type.ERROR)
                    .show();
            return;
        }

        userSelector.setItems(users);
        userSelector.setItemLabelGenerator(AppUser::getName);

        AppUser current = currentAppUserProvider.getCurrentAppUser();
        if (current != null) {
            users.stream()
                    .filter(user -> user.getId().equals(current.getId()))
                    .findFirst()
                    .ifPresent(userSelector::setValue);
        }

        userSelector.addValueChangeListener(this::onUserSelected);
    }

    private void onUserSelected(HasValue.ValueChangeEvent<AppUser> event) {
        if (!event.isFromClient()) {
            return;
        }
        AppUser user = event.getValue();
        currentAppUserProvider.setCurrentAppUserId(user != null ? user.getId() : null);
    }
}
