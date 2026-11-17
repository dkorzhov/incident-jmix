package com.company.incidentjmix.rest;

import com.company.incidentjmix.entity.AppUser;
import com.company.incidentjmix.entity.Incident;
import com.company.incidentjmix.entity.IncidentStatus;
import com.company.incidentjmix.service.AppUserService;
import com.company.incidentjmix.service.CommentService;
import com.company.incidentjmix.service.CreateCommand;
import com.company.incidentjmix.service.DashboardService;
import com.company.incidentjmix.service.IncidentService;
import com.company.incidentjmix.test_support.AuthenticatedAsAdmin;
import com.company.incidentjmix.test_support.DomainCleanup;
import io.jmix.core.DataManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Base for REST API integration tests (DMI-17): full Spring context with the
 * real security filter chains, MockMvc built manually with springSecurity()
 * so /api/** requests run as anonymous exactly like in production. Test data
 * is set up through the domain services under admin authentication; every
 * created incident is tracked and removed after each test (shared file HSQLDB).
 */
@SpringBootTest
@ActiveProfiles("test")
@ExtendWith(AuthenticatedAsAdmin.class)
abstract class AbstractRestApiTest {

    @Autowired
    protected WebApplicationContext webApplicationContext;
    @Autowired
    protected DataManager dataManager;
    @Autowired
    protected IncidentService incidentService;
    @Autowired
    protected CommentService commentService;
    @Autowired
    protected AppUserService appUserService;
    @Autowired
    protected DashboardService dashboardService;
    @Autowired
    protected ObjectMapper objectMapper;

    protected MockMvc mockMvc;
    protected DomainCleanup cleanup;
    protected AppUser actor;

    @BeforeEach
    void setUpRestApi() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
        cleanup = new DomainCleanup(dataManager);
        actor = appUserService.getById(1);
    }

    @AfterEach
    void tearDownRestApi() {
        cleanup.cleanup();
    }

    protected Incident createIncident(String title, String category, String priority, Integer assigneeId) {
        Incident incident = incidentService.create(actor,
                new CreateCommand(title, null, category, priority, assigneeId));
        cleanup.track(incident);
        return incident;
    }

    protected Incident createInProgress(String title, String category, String priority) {
        Incident incident = createIncident(title, category, priority, null);
        return incidentService.changeStatus(actor, incident.getId(), IncidentStatus.IN_PROGRESS, null);
    }

    protected Incident createResolved(String title, String category, String priority, String resolution) {
        Incident incident = createInProgress(title, category, priority);
        return incidentService.changeStatus(actor, incident.getId(), IncidentStatus.RESOLVED, resolution);
    }

    protected Incident createClosed(String title, String category, String priority, String resolution) {
        Incident incident = createResolved(title, category, priority, resolution);
        return incidentService.changeStatus(actor, incident.getId(), IncidentStatus.CLOSED, null);
    }

    protected long incidentCount() {
        return dataManager.loadValue("select count(e) from inc_Incident e", Long.class).one();
    }

    protected long historyCount(String incidentId) {
        return dataManager.loadValue(
                        "select count(e) from inc_HistoryEvent e where e.incident.id = :id", Long.class)
                .parameter("id", incidentId)
                .one();
    }

    protected long totalHistoryCount() {
        return dataManager.loadValue("select count(e) from inc_HistoryEvent e", Long.class).one();
    }

    /**
     * Executes a MockMvc request and restores the test thread's admin
     * authentication afterwards: the security filter chain clears the
     * SecurityContextHolder when the request completes, which would break
     * subsequent service calls, assertions and cleanup.
     */
    protected ResultActions performApi(MockHttpServletRequestBuilder builder) throws Exception {
        Authentication admin = SecurityContextHolder.getContext().getAuthentication();
        try {
            return mockMvc.perform(builder);
        } finally {
            SecurityContextHolder.getContext().setAuthentication(admin);
        }
    }

    protected ResultActions getApi(String url) throws Exception {
        return performApi(get(url).header("X-User-Id", "1"));
    }

    protected ResultActions getApiNoHeader(String url) throws Exception {
        return performApi(get(url));
    }

    protected ResultActions postApi(String url, String body) throws Exception {
        return performApi(post(url)
                .header("X-User-Id", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    protected ResultActions patchApi(String url, String body) throws Exception {
        return performApi(patch(url)
                .header("X-User-Id", "1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    protected JsonNode bodyJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
