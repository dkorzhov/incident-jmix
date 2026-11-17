package com.company.incidentjmix.service;

import com.company.incidentjmix.entity.AppUser;
import com.company.incidentjmix.entity.Incident;
import com.company.incidentjmix.entity.IncidentStatus;
import io.jmix.core.DataManager;
import io.jmix.core.security.SystemAuthenticator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Demo data loader (TASK.md §6, ADR-005). Runs after the context is ready
 * ONLY when app.seed-demo=true (env SEED_DEMO). The startup thread has no
 * authenticated user, so all data access is wrapped in
 * SystemAuthenticator.runWithSystem. Idempotent: skipped when at least one
 * incident exists (BT-35). Incidents and history are created exclusively
 * through the domain services with real AppUser actors — no manual SQL.
 * Users themselves are always preloaded by Liquibase (020-init-app-user.xml).
 */
@Component
public class DemoDataSeeder {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final DataManager dataManager;
    private final IncidentService incidentService;
    private final CommentService commentService;
    private final AppUserService appUserService;
    private final SystemAuthenticator systemAuthenticator;

    @Value("${app.seed-demo:false}")
    private boolean seedDemo;

    public DemoDataSeeder(DataManager dataManager,
                          IncidentService incidentService,
                          CommentService commentService,
                          AppUserService appUserService,
                          SystemAuthenticator systemAuthenticator) {
        this.dataManager = dataManager;
        this.incidentService = incidentService;
        this.commentService = commentService;
        this.appUserService = appUserService;
        this.systemAuthenticator = systemAuthenticator;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (seedDemo) {
            seed();
        }
    }

    /**
     * Seeds demo data under the system user; safe to call from a thread
     * without authentication.
     */
    public void seed() {
        systemAuthenticator.runWithSystem(this::doSeed);
    }

    private void doSeed() {
        long existing = dataManager.loadValue("select count(e) from inc_Incident e", Long.class).one();
        if (existing > 0) {
            log.info("Demo seed skipped: {} incident(s) already exist", existing);
            return;
        }

        AppUser ivanov = requireUser(1);
        AppUser petrov = requireUser(2);
        AppUser sidorov = requireUser(3);
        AppUser kuznetsova = requireUser(4);

        // 1. New, Critical
        incidentService.create(kuznetsova, new CreateCommand(
                "Проблемы с VPN", "Не подключается VPN из дома", "Network", "Critical", null));

        // 2. New, Medium
        incidentService.create(kuznetsova, new CreateCommand(
                "Не печатает принтер", "Принтер в бухгалтерии не печатает документы", "Workplace", "Medium", null));

        // 3. In Progress, High, with a comment
        Incident mail = incidentService.create(kuznetsova, new CreateCommand(
                "Не работает почта", "Не отправляются письма во внешние домены", "Mail", "High", ivanov.getId()));
        incidentService.changeStatus(ivanov, mail.getId(), IncidentStatus.IN_PROGRESS, null);
        commentService.add(ivanov, mail.getId(), "Проверяю почтовый сервер, вижу очередь неотправленных писем");

        // 4. In Progress, Critical
        Incident database = incidentService.create(kuznetsova, new CreateCommand(
                "Медленно работает БД", "Запросы к базе выполняются по 30 секунд", "Database", "Critical", petrov.getId()));
        incidentService.changeStatus(petrov, database.getId(), IncidentStatus.IN_PROGRESS, null);

        // 5. Resolved today, High — resolved event createdAt = seed moment (today UTC)
        Incident portal = incidentService.create(kuznetsova, new CreateCommand(
                "Ошибка на портале", "Падает форма подачи заявки", "Portal", "High", sidorov.getId()));
        incidentService.changeStatus(sidorov, portal.getId(), IncidentStatus.IN_PROGRESS, null);
        incidentService.changeStatus(sidorov, portal.getId(), IncidentStatus.RESOLVED,
                "Исправлена валидация формы, обновление задеплоено");

        // 6. Closed, Low — full cycle: created → assigned → status_changed → resolved → status_changed
        Incident workplace = incidentService.create(kuznetsova, new CreateCommand(
                "Заменить мышь", "Не работает колесо прокрутки мыши", "Workplace", "Low", null));
        incidentService.update(ivanov, workplace.getId(), new PatchCommand().assigneeId(ivanov.getId()));
        incidentService.changeStatus(ivanov, workplace.getId(), IncidentStatus.IN_PROGRESS, null);
        incidentService.changeStatus(ivanov, workplace.getId(), IncidentStatus.RESOLVED, "Мышь заменена на новую");
        incidentService.changeStatus(kuznetsova, workplace.getId(), IncidentStatus.CLOSED, null);

        log.info("Demo seed: 6 incidents created");
    }

    private AppUser requireUser(int id) {
        AppUser user = appUserService.getById(id);
        if (user == null) {
            throw new IllegalStateException("Предзагруженный пользователь id=" + id + " не найден");
        }
        return user;
    }
}
