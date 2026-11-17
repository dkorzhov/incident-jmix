package com.company.incidentjmix.test_support;

import com.company.incidentjmix.entity.AppUser;
import com.company.incidentjmix.entity.Incident;
import com.company.incidentjmix.service.AppUserService;
import com.company.incidentjmix.service.CommentService;
import com.company.incidentjmix.service.CreateCommand;
import com.company.incidentjmix.service.IncidentService;
import io.jmix.core.DataManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the PR #3 review fix: purgeAllIncidents() must restore the INC-NNN
 * counter baseline, not only remove rows, so id assertions stay relative
 * across runs and a following create() reuses the pre-purge ids.
 */
@SpringBootTest
@ActiveProfiles("test")
@ExtendWith(AuthenticatedAsAdmin.class)
class DomainCleanupTest {

    @Autowired
    private DataManager dataManager;
    @Autowired
    private IncidentService incidentService;
    @Autowired
    private CommentService commentService;
    @Autowired
    private AppUserService appUserService;

    @Test
    void purgeAllIncidentsRemovesChildrenAndRestoresCounterBaseline() {
        DomainCleanup cleanup = new DomainCleanup(dataManager);
        AppUser actor = appUserService.getById(1);
        try {
            Incident first = incidentService.create(actor,
                    new CreateCommand("Очистка один", null, "Mail", "Low", null));
            incidentService.create(actor,
                    new CreateCommand("Очистка два", null, "Network", "High", 2));
            commentService.add(actor, first.getId(), "Комментарий для очистки");
            assertThat(cleanup.counterValue()).isEqualTo(cleanup.counterBaseline() + 2);

            cleanup.purgeAllIncidents();

            assertThat(cleanup.counterValue()).isEqualTo(cleanup.counterBaseline());
            assertThat(count("select count(e) from inc_Incident e")).isZero();
            assertThat(count("select count(e) from inc_Comment e")).isZero();
            assertThat(count("select count(e) from inc_HistoryEvent e")).isZero();

            // the next created incident reuses the first pre-purge id
            Incident again = incidentService.create(actor,
                    new CreateCommand("После очистки", null, "Mail", "Low", null));
            assertThat(again.getId()).isEqualTo(first.getId());
            cleanup.track(again);
        } finally {
            cleanup.cleanup();
        }
    }

    private long count(String query) {
        return dataManager.loadValue(query, Long.class).one();
    }
}
