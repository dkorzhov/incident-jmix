package com.company.incidentjmix.rest;

import com.company.incidentjmix.entity.Incident;
import com.company.incidentjmix.entity.IncidentStatus;
import com.company.incidentjmix.service.IncidentService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET/POST /api/incidents, GET/PATCH /api/incidents/{id}, DELETE → 405
 * (TASK.md §3, ADR-002; AC-LIST, AC-GET, AC-PATCH, AC-DEL, AC-ERR, AC-HIS).
 */
class IncidentApiTest extends AbstractRestApiTest {

    // ---------- GET /api/incidents ----------

    @Test
    void listReturnsSnakeCaseIncidentsSortedByCreatedAtDesc() throws Exception {
        Incident first = createIncident("Первый инцидент", "Network", "Low", null);
        Incident second = createIncident("Второй инцидент", "Mail", "High", 2);
        Incident third = createIncident("Третий инцидент", "Portal", "Critical", null);

        MvcResult result = getApiNoHeader("/api/incidents")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[1].assignee_id").value(2))
                .andExpect(jsonPath("$[0].created_at").exists())
                .andExpect(jsonPath("$[0].updated_at").exists())
                .andExpect(jsonPath("$[0].assigneeId").doesNotExist())
                .andExpect(jsonPath("$[0].createdAt").doesNotExist())
                .andReturn();

        assertThat(ids(result)).containsExactly(third.getId(), second.getId(), first.getId());
        JsonNode secondJson = bodyJson(result).get(1);
        assertThat(secondJson.get("assignee_id").asInt()).isEqualTo(2);
        assertThat(secondJson.get("status").asString()).isEqualTo("New");
    }

    @Test
    void listCombinesQStatusAndPriorityFilters() throws Exception {
        Incident newCritical = createIncident("Не работает почта", "Mail", "Critical", null);
        createIncident("Другая почта", "Mail", "Low", null);
        Incident progressCritical = createInProgress("Критичный портал", "Portal", "Critical");
        createIncident("Не работает сеть", "Network", "Critical", null);

        // q: case-insensitive substring on title
        MvcResult byTitle = performApi(
                        get("/api/incidents").param("q", "ПОЧТА"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(ids(byTitle)).containsExactlyInAnyOrder(newCritical.getId(),
                incidentsByTitle("Другая почта"));

        // q: substring on id
        MvcResult byId = performApi(
                        get("/api/incidents").param("q", newCritical.getId().toLowerCase()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(ids(byId)).containsExactly(newCritical.getId());

        // status + priority combined with AND
        MvcResult filtered = performApi(
                        get("/api/incidents")
                                .param("status", "In Progress")
                                .param("priority", "Critical"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(ids(filtered)).containsExactly(progressCritical.getId());

        // status only
        MvcResult newOnly = performApi(
                        get("/api/incidents").param("status", "New"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(ids(newOnly)).hasSize(3);
    }

    @Test
    void listUnknownFilterValuesReturn400NamingParameter() throws Exception {
        performApi(get("/api/incidents").param("status", "Bogus"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("status")));

        performApi(get("/api/incidents").param("priority", "Bogus"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("priority")));
    }

    @Test
    void listOnEmptyDatabaseReturnsEmptyArray() throws Exception {
        cleanup.purgeAllIncidents();

        getApiNoHeader("/api/incidents")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void listWithUnknownUserHeaderReturns401() throws Exception {
        performApi(get("/api/incidents").header("X-User-Id", "999"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").exists());
    }

    // ---------- POST /api/incidents ----------

    @Test
    void createReturns201WithServerFieldsAndCreatedHistoryEvent() throws Exception {
        long counterBefore = cleanup.counterValue();

        MvcResult result = postApi("/api/incidents", """
                {
                  "id": "HACK-999",
                  "title": "Не работает почта",
                  "description": "Подробности проблемы",
                  "category": "Mail",
                  "priority": "High",
                  "assignee_id": 2,
                  "unknown_field": true
                }
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(matchesPattern("INC-\\d+")))
                .andExpect(jsonPath("$.status").value("New"))
                .andExpect(jsonPath("$.title").value("Не работает почта"))
                .andExpect(jsonPath("$.assignee_id").value(2))
                .andExpect(jsonPath("$.resolution").value(nullValue()))
                .andExpect(jsonPath("$.created_at").exists())
                .andExpect(jsonPath("$.updated_at").exists())
                .andReturn();

        String id = bodyJson(result).get("id").asString();
        cleanup.track(id);
        assertThat(id).isEqualTo(String.format("INC-%03d", counterBefore));

        // exactly one created event, actor = header user, payload.after present
        getApi("/api/incidents/" + id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.history.length()").value(1))
                .andExpect(jsonPath("$.history[0].type").value("created"))
                .andExpect(jsonPath("$.history[0].actor_id").value(1))
                .andExpect(jsonPath("$.history[0].incident_id").value(id))
                .andExpect(jsonPath("$.history[0].payload.after.title").value("Не работает почта"))
                .andExpect(jsonPath("$.history[0].payload.after.status").value("New"))
                .andExpect(jsonPath("$.history[0].payload.after.assignee_id").value(2));
    }

    @Test
    void createValidationErrorsReturn400ListingFieldsAndWriteNothing() throws Exception {
        long incidentsBefore = incidentCount();
        long historyBefore = totalHistoryCount();

        postApi("/api/incidents", """
                {
                  "title": "abc",
                  "description": "%s",
                  "category": "Bogus"
                }
                """.formatted("x".repeat(2001)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(allOf(
                        containsString("title"),
                        containsString("description"),
                        containsString("category"),
                        containsString("priority"))));

        assertThat(incidentCount()).isEqualTo(incidentsBefore);
        assertThat(totalHistoryCount()).isEqualTo(historyBefore);
    }

    @Test
    void createWithUnknownAssigneeReturns400() throws Exception {
        postApi("/api/incidents", """
                {"title": "Валидный заголовок", "category": "Mail", "priority": "Low", "assignee_id": 999}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("assignee_id")));
    }

    @Test
    void createWithoutValidUserHeaderReturns401BeforeAnyChange() throws Exception {
        long incidentsBefore = incidentCount();
        long historyBefore = totalHistoryCount();
        String body = """
                {"title": "Валидный заголовок", "category": "Mail", "priority": "Low"}
                """;

        performApi(post("/api/incidents").contentType("application/json").content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").exists());
        performApi(post("/api/incidents").header("X-User-Id", "abc")
                        .contentType("application/json").content(body))
                .andExpect(status().isUnauthorized());
        performApi(post("/api/incidents").header("X-User-Id", "999")
                        .contentType("application/json").content(body))
                .andExpect(status().isUnauthorized());

        assertThat(incidentCount()).isEqualTo(incidentsBefore);
        assertThat(totalHistoryCount()).isEqualTo(historyBefore);
    }

    @Test
    void createWithBrokenJsonReturns400() throws Exception {
        postApi("/api/incidents", "{broken json")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").exists());
    }

    // ---------- GET /api/incidents/{id} ----------

    @Test
    void getReturnsCardWithOrderedCommentsAndHistory() throws Exception {
        Incident incident = createIncident("Карточка инцидента", "Network", "Medium", null);
        String id = incident.getId();
        patchApi("/api/incidents/" + id, "{\"assignee_id\": 2}")
                .andExpect(status().isOk());
        var first = commentService.add(actor, id, "Первый комментарий");
        var second = commentService.add(actor, id, "Второй комментарий");
        incidentService.changeStatus(actor, id, IncidentStatus.IN_PROGRESS, null);

        getApiNoHeader("/api/incidents/" + id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("In Progress"))
                .andExpect(jsonPath("$.assignee_id").value(2))
                .andExpect(jsonPath("$.comments.length()").value(2))
                .andExpect(jsonPath("$.comments[0].id").value(first.getId()))
                .andExpect(jsonPath("$.comments[0].text").value("Первый комментарий"))
                .andExpect(jsonPath("$.comments[0].author_id").value(1))
                .andExpect(jsonPath("$.comments[0].incident_id").value(id))
                .andExpect(jsonPath("$.comments[1].id").value(second.getId()))
                // history desc: latest status_changed first, created last
                .andExpect(jsonPath("$.history.length()").value(5))
                .andExpect(jsonPath("$.history[0].type").value("status_changed"))
                .andExpect(jsonPath("$.history[1].type").value("commented"))
                .andExpect(jsonPath("$.history[2].type").value("commented"))
                .andExpect(jsonPath("$.history[3].type").value("assigned"))
                .andExpect(jsonPath("$.history[4].type").value("created"))
                .andExpect(jsonPath("$.history[0].payload.before.status").value("New"))
                .andExpect(jsonPath("$.history[0].payload.after.status").value("In Progress"));
    }

    @Test
    void getUnknownIdReturns404WithDetail() throws Exception {
        getApiNoHeader("/api/incidents/INC-99999")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").exists());
    }

    @Test
    void getClosedIncidentIsStillReadable() throws Exception {
        Incident closed = createClosed("Закрытый инцидент", "Other", "Low", "Решено");

        getApiNoHeader("/api/incidents/" + closed.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Closed"))
                .andExpect(jsonPath("$.resolution").value("Решено"));
    }

    // ---------- PATCH /api/incidents/{id} ----------

    @Test
    void patchUpdatesFieldsAndWritesAggregatedHistory() throws Exception {
        Incident incident = createIncident("Исходный заголовок", "Network", "Low", null);
        String id = incident.getId();

        patchApi("/api/incidents/" + id, """
                {"title": "Новый заголовок", "description": "Новое описание", "category": "Mail"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Новый заголовок"))
                .andExpect(jsonPath("$.description").value("Новое описание"))
                .andExpect(jsonPath("$.category").value("Mail"))
                .andExpect(jsonPath("$.priority").value("Low"));

        patchApi("/api/incidents/" + id, "{\"priority\": \"Critical\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priority").value("Critical"));

        patchApi("/api/incidents/" + id, "{\"assignee_id\": 2}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignee_id").value(2));

        JsonNode history = bodyJson(getApi("/api/incidents/" + id).andReturn()).get("history");
        // desc order: assigned, priority_changed, fields_updated, created
        assertThat(history.get(0).get("type").asString()).isEqualTo("assigned");
        assertThat(history.get(0).get("payload").get("before").get("assignee_id").isNull()).isTrue();
        assertThat(history.get(0).get("payload").get("after").get("assignee_id").asInt()).isEqualTo(2);
        assertThat(history.get(1).get("type").asString()).isEqualTo("priority_changed");
        assertThat(history.get(1).get("payload").get("before").get("priority").asString()).isEqualTo("Low");
        assertThat(history.get(1).get("payload").get("after").get("priority").asString()).isEqualTo("Critical");
        assertThat(history.get(2).get("type").asString()).isEqualTo("fields_updated");
        JsonNode before = history.get(2).get("payload").get("before");
        JsonNode after = history.get(2).get("payload").get("after");
        assertThat(before.get("title").asString()).isEqualTo("Исходный заголовок");
        assertThat(before.get("category").asString()).isEqualTo("Network");
        assertThat(before.get("description").isNull()).isTrue();
        assertThat(after.get("title").asString()).isEqualTo("Новый заголовок");
        assertThat(after.get("description").asString()).isEqualTo("Новое описание");
        assertThat(after.get("category").asString()).isEqualTo("Mail");
        // only changed fields are aggregated
        assertThat(before.has("priority")).isFalse();
        assertThat(before.has("assignee_id")).isFalse();
        assertThat(history.get(3).get("type").asString()).isEqualTo("created");
    }

    @Test
    void patchCombinedChangeWritesOneEventPerType() throws Exception {
        Incident incident = createIncident("Комбинированное изменение", "Network", "Low", 1);
        String id = incident.getId();
        long historyBefore = historyCount(id);

        patchApi("/api/incidents/" + id, """
                {"title": "Другой заголовок", "priority": "High", "assignee_id": 3}
                """)
                .andExpect(status().isOk());

        assertThat(historyCount(id)).isEqualTo(historyBefore + 3);
        JsonNode history = bodyJson(getApi("/api/incidents/" + id).andReturn()).get("history");
        List<String> types = new ArrayList<>();
        history.forEach(e -> types.add(e.get("type").asString()));
        assertThat(types).containsExactly("assigned", "priority_changed", "fields_updated", "created");
    }

    @Test
    void patchEmptyBodyIsNoOpWithoutEventsOrUpdatedAtBump() throws Exception {
        Incident incident = createIncident("Без изменений", "Network", "Low", 2);
        String id = incident.getId();
        long historyBefore = historyCount(id);

        MvcResult result = patchApi("/api/incidents/" + id, "{}")
                .andExpect(status().isOk())
                .andReturn();

        assertThat(historyCount(id)).isEqualTo(historyBefore);
        assertThat(OffsetDateTime.parse(bodyJson(result).get("updated_at").asString()).toInstant())
                .isEqualTo(incident.getUpdatedAt().toInstant());
    }

    @Test
    void patchSameValuesIsNoOp() throws Exception {
        Incident incident = createIncident("Те же значения", "Mail", "High", 2);
        String id = incident.getId();
        long historyBefore = historyCount(id);

        MvcResult result = patchApi("/api/incidents/" + id, """
                {"title": "Те же значения", "category": "Mail", "priority": "High", "assignee_id": 2}
                """)
                .andExpect(status().isOk())
                .andReturn();

        assertThat(historyCount(id)).isEqualTo(historyBefore);
        assertThat(OffsetDateTime.parse(bodyJson(result).get("updated_at").asString()).toInstant())
                .isEqualTo(incident.getUpdatedAt().toInstant());
    }

    @Test
    void patchExplicitNullAssigneeUnassigns() throws Exception {
        Incident incident = createIncident("Снятие ответственного", "Network", "Low", 2);
        String id = incident.getId();

        patchApi("/api/incidents/" + id, "{\"assignee_id\": null}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignee_id").value(nullValue()));

        JsonNode history = bodyJson(getApi("/api/incidents/" + id).andReturn()).get("history");
        assertThat(history.get(0).get("type").asString()).isEqualTo("assigned");
        assertThat(history.get(0).get("payload").get("before").get("assignee_id").asInt()).isEqualTo(2);
        assertThat(history.get(0).get("payload").get("after").get("assignee_id").isNull()).isTrue();
    }

    @Test
    void patchDescriptionOver2000Returns400AndKeepsData() throws Exception {
        // review finding on PR #3: description length must be validated on update too
        Incident incident = createIncident("Длинное описание", "Network", "Low", null);
        String id = incident.getId();
        long historyBefore = historyCount(id);

        patchApi("/api/incidents/" + id,
                "{\"description\": \"" + "x".repeat(2001) + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("description")));

        assertThat(historyCount(id)).isEqualTo(historyBefore);
        getApi("/api/incidents/" + id)
                .andExpect(jsonPath("$.description").value(nullValue()));
    }

    @Test
    void patchWrongFieldTypesReturn400NamingField() throws Exception {
        Incident incident = createIncident("Неверные типы", "Network", "Low", null);
        String id = incident.getId();

        patchApi("/api/incidents/" + id, "{\"assignee_id\": \"abc\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("assignee_id")));
        patchApi("/api/incidents/" + id, "{\"title\": 123}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("title")));
        patchApi("/api/incidents/" + id, "[1, 2]")
                .andExpect(status().isBadRequest());
        patchApi("/api/incidents/" + id, "null")
                .andExpect(status().isBadRequest());
    }

    @Test
    void patchClosedIncidentReturns409() throws Exception {
        Incident closed = createClosed("Закрыт для правок", "Other", "Low", "Решено");

        patchApi("/api/incidents/" + closed.getId(), "{\"title\": \"Попытка правки\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(IncidentService.CLOSED_MESSAGE));
    }

    @Test
    void patchUnknownIdReturns404AndMissingHeaderReturns401() throws Exception {
        patchApi("/api/incidents/INC-99999", "{\"title\": \"Не найдено\"}")
                .andExpect(status().isNotFound());

        Incident incident = createIncident("Без заголовка пользователя", "Network", "Low", null);
        performApi(patch("/api/incidents/" + incident.getId())
                        .contentType("application/json")
                        .content("{\"title\": \"Другой заголовок\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ---------- DELETE is out of scope ----------

    @Test
    void deleteReturns405AndKeepsIncident() throws Exception {
        Incident incident = createIncident("Удаление запрещено", "Network", "Low", null);

        performApi(delete("/api/incidents/" + incident.getId()).header("X-User-Id", "1"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.detail").exists());

        assertThat(incidentCount()).isEqualTo(1);
        getApi("/api/incidents/" + incident.getId())
                .andExpect(status().isOk());
    }

    // ---------- helpers ----------

    private List<String> ids(MvcResult result) throws Exception {
        JsonNode array = bodyJson(result);
        List<String> ids = new ArrayList<>();
        array.forEach(node -> ids.add(node.get("id").asString()));
        return ids;
    }

    private String incidentsByTitle(String title) {
        return dataManager.load(Incident.class)
                .query("select e from inc_Incident e where e.title = :title")
                .parameter("title", title)
                .one()
                .getId();
    }
}
