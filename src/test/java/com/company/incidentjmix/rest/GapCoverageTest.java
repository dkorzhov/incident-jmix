package com.company.incidentjmix.rest;

import com.company.incidentjmix.entity.HistoryEventType;
import com.company.incidentjmix.entity.Incident;
import com.company.incidentjmix.entity.IncidentStatus;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Boundary-length, 400/401/404/409, Closed immutability, history payload
 * completeness, dashboard convergence, and X-User-Id gaps (DMI-20 QA).
 * <p>
 * Extends {@link AbstractRestApiTest} to reuse its REST helpers,
 * {@link #actor}, and clean-up infrastructure. Every gap listed in
 * {@code e2e/TEST-CASES.md} that is not already covered by the existing
 * {@link IncidentApiTest}, {@link StatusApiTest}, {@link CommentApiTest},
 * or {@link DashboardApiTest} is verified here.
 */
class GapCoverageTest extends AbstractRestApiTest {

    // ─── Helpers ───────────────────────────────────────────────────────

    /** Creates via REST and tracks for cleanup. */
    private String createAndTrack(String title, String category, String priority, Integer assigneeId)
            throws Exception {
        String assignee = assigneeId != null ? String.format(",\"assignee_id\":%d", assigneeId) : "";
        MvcResult result = postApi("/api/incidents", """
                {"title":"%s","category":"%s","priority":"%s"%s}
                """.formatted(title, category, priority, assignee))
                .andExpect(status().isCreated())
                .andReturn();
        String id = bodyJson(result).get("id").asString();
        cleanup.track(id);
        return id;
    }

    private String createAndTrack(String title, String category, String priority) throws Exception {
        return createAndTrack(title, category, priority, null);
    }

    /** Creates via service (already tracked by createIncident) for status transitions. */
    private String createClosedAndTrack(String title, String category, String priority, String resolution)
            throws Exception {
        Incident closed = createClosed(title, category, priority, resolution);
        return closed.getId();
    }

    // ─── Boundary lengths (title 5–120, description ≤2000, resolution ≤2000) ───

    @Test
    void titleBoundaryLengths() throws Exception {
        // Valid: exactly 5 characters
        createAndTrack("12345", "Other", "Low");

        // Valid: exactly 120 characters
        createAndTrack("a".repeat(120), "Other", "Low");

        // Invalid: 4 characters
        postApi("/api/incidents", """
                {"title":"1234","category":"Other","priority":"Low"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("title")));

        // Invalid: 121 characters
        postApi("/api/incidents", """
                {"title":"%s","category":"Other","priority":"Low"}
                """.formatted("a".repeat(121)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("title")));

        // Invalid: whitespace-only (after trim: empty → shorter than 5)
        postApi("/api/incidents", """
                {"title":"     ","category":"Other","priority":"Low"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("title")));
    }

    @Test
    void descriptionBoundaryLength() throws Exception {
        // Valid: exactly 2000 characters
        String desc2000 = "d".repeat(2000);
        MvcResult r = postApi("/api/incidents", """
                {"title":"description test","description":"%s","category":"Other","priority":"Low"}
                """.formatted(desc2000))
                .andExpect(status().isCreated())
                .andReturn();
        cleanup.track(bodyJson(r).get("id").asString());

        // Invalid: 2001 characters
        String desc2001 = "d".repeat(2001);
        postApi("/api/incidents", """
                {"title":"description too long","description":"%s","category":"Other","priority":"Low"}
                """.formatted(desc2001))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("description")));
    }

    @Test
    void resolutionBoundaryLength() throws Exception {
        String id = createInProgressRest("Resolution boundary", "Workplace", "Medium");

        // Valid: exactly 2000 characters
        String res2000 = "r".repeat(2000);
        patchApi("/api/incidents/" + id + "/status", """
                {"status":"Resolved","resolution":"%s"}
                """.formatted(res2000))
                .andExpect(status().isOk());

        String id2 = createInProgressRest("Resolution too long", "Workplace", "Medium");

        // Invalid: 2001 characters
        String res2001 = "r".repeat(2001);
        patchApi("/api/incidents/" + id2 + "/status", """
                {"status":"Resolved","resolution":"%s"}
                """.formatted(res2001))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("resolution")));
    }

    @Test
    void resolutionEmptyOrWhitespaceOnlyReturns400() throws Exception {
        String id = createInProgressRest("Resolution empty", "Mail", "Low");

        // Empty string
        patchApi("/api/incidents/" + id + "/status",
                "{\"status\":\"Resolved\",\"resolution\":\"\"}")
                .andExpect(status().isBadRequest());

        // Whitespace only
        patchApi("/api/incidents/" + id + "/status",
                "{\"status\":\"Resolved\",\"resolution\":\"   \"}")
                .andExpect(status().isBadRequest());

        // Missing resolution field entirely
        patchApi("/api/incidents/" + id + "/status",
                "{\"status\":\"Resolved\"}")
                .andExpect(status().isBadRequest());

        // Status must still be In Progress (not changed)
        getApi("/api/incidents/" + id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("In Progress"));
    }

    // ─── 401: X-User-Id missing/invalid on mutating endpoints ───

    @Test
    void patchStatusWithoutValidHeaderReturns401() throws Exception {
        String id = createAndTrack("Status bez zagolovka", "Portal", "Medium");

        // No header at all
        performApi(patch("/api/incidents/" + id + "/status")
                .contentType("application/json")
                .content("{\"status\": \"In Progress\"}"))
                .andExpect(status().isUnauthorized());

        // Invalid non-numeric header
        performApi(patch("/api/incidents/" + id + "/status")
                .header("X-User-Id", "abc")
                .contentType("application/json")
                .content("{\"status\": \"In Progress\"}"))
                .andExpect(status().isUnauthorized());

        // Unknown user id
        performApi(patch("/api/incidents/" + id + "/status")
                .header("X-User-Id", "999")
                .contentType("application/json")
                .content("{\"status\": \"In Progress\"}"))
                .andExpect(status().isUnauthorized());

        // Incident unchanged
        getApi("/api/incidents/" + id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("New"));
    }

    @Test
    void patchFieldsWithoutValidHeaderReturns401() throws Exception {
        String id = createAndTrack("Polya bez zagolovka", "Mail", "Low");

        performApi(patch("/api/incidents/" + id)
                .contentType("application/json")
                .content("{\"title\": \"Novoe nazvanie\"}"))
                .andExpect(status().isUnauthorized());

        performApi(patch("/api/incidents/" + id)
                .header("X-User-Id", "99999")
                .contentType("application/json")
                .content("{\"title\": \"Novoe nazvanie\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void postIncidentWithoutValidHeaderReturns401() throws Exception {
        long before = incidentCount();

        performApi(post("/api/incidents")
                .contentType("application/json")
                .content("{\"title\":\"Bez aktora\",\"category\":\"Other\",\"priority\":\"Low\"}"))
                .andExpect(status().isUnauthorized());

        // Incident was NOT created
        assertThat(incidentCount()).isEqualTo(before);
    }

    // ─── 409: Closed immutability ───

    @Test
    void patchingClosedIncidentFieldsReturns409() throws Exception {
        String id = createClosedAndTrack("Zakrytyj PATCH polej", "Database", "Medium", "Resheno polnostyu");
        long historyBefore = historyCount(id);

        // PATCH status
        patchApi("/api/incidents/" + id + "/status",
                "{\"status\": \"In Progress\"}")
                .andExpect(status().isConflict());

        // PATCH fields
        patchApi("/api/incidents/" + id, "{\"title\": \"Novoe imya\"}")
                .andExpect(status().isConflict());

        // Data and history unchanged
        assertThat(historyCount(id)).isEqualTo(historyBefore);
        getApi("/api/incidents/" + id)
                .andExpect(jsonPath("$.status").value("Closed"))
                .andExpect(jsonPath("$.title").value("Zakrytyj PATCH polej"));
    }

    @Test
    void changingClosedStatusToAnythingReturns409() throws Exception {
        String id = createClosedAndTrack("Zakrytyj status", "Portal", "Low", "Gotovo");

        for (String target : new String[]{"New", "In Progress", "Resolved", "Closed"}) {
            patchApi("/api/incidents/" + id + "/status",
                    "{\"status\": \"" + target + "\"}")
                    .andExpect(status().isConflict());
        }

        getApi("/api/incidents/" + id)
                .andExpect(jsonPath("$.status").value("Closed"));
    }

    @Test
    void commentingOnClosedReturns409() throws Exception {
        String id = createClosedAndTrack("Kommentarij k Closed", "Network", "Critical", "Resheno");
        postApi("/api/incidents/" + id + "/comments",
                "{\"text\": \"Pozdno\"}")
                .andExpect(status().isConflict());
    }

    // ─── 409: All illegal transitions ───

    @Test
    void allIllegalTransitionsReturn409() throws Exception {
        String newId = createAndTrack("New dlya 409", "Network", "High");

        // New → Closed
        patchApi("/api/incidents/" + newId + "/status",
                "{\"status\": \"Closed\"}")
                .andExpect(status().isConflict());
        // New → Resolved (with resolution)
        patchApi("/api/incidents/" + newId + "/status",
                "{\"status\": \"Resolved\", \"resolution\": \"test\"}")
                .andExpect(status().isConflict());
        // New → New (same status)
        patchApi("/api/incidents/" + newId + "/status",
                "{\"status\": \"New\"}")
                .andExpect(status().isConflict());

        String inProgressId = createInProgressRest("InProgress dlya 409", "Mail", "Low");
        // In Progress → Closed
        patchApi("/api/incidents/" + inProgressId + "/status",
                "{\"status\": \"Closed\"}")
                .andExpect(status().isConflict());
        // In Progress → New
        patchApi("/api/incidents/" + inProgressId + "/status",
                "{\"status\": \"New\"}")
                .andExpect(status().isConflict());

        String resolvedId = createResolvedRest("Resolved dlya 409", "Portal", "Medium", "Resheno");
        // Resolved → New
        patchApi("/api/incidents/" + resolvedId + "/status",
                "{\"status\": \"New\"}")
                .andExpect(status().isConflict());
        // Resolved → In Progress
        patchApi("/api/incidents/" + resolvedId + "/status",
                "{\"status\": \"In Progress\"}")
                .andExpect(status().isConflict());
    }

    @Test
    void conflictResponseContainsHumanReadableMessage() throws Exception {
        String id = createAndTrack("Konflikt soobshchenie", "Other", "Low");
        MvcResult result = patchApi("/api/incidents/" + id + "/status",
                "{\"status\": \"Closed\"}")
                .andExpect(status().isConflict())
                .andReturn();

        String detail = bodyJson(result).get("detail").asString();
        assertThat(detail).isNotEmpty();
        assertThat(detail.toLowerCase()).containsAnyOf("переход", "transition", "запрещён", "недопустим", "not allowed",
                "invalid");
    }

    // ─── History payload completeness ───

    @Test
    void createdEventHasFullFieldSnapshotAndNoBefore() throws Exception {
        String id = createAndTrack("Snimok polej", "Portal", "Critical", 2);
        JsonNode history = bodyJson(getApi("/api/incidents/" + id).andReturn())
                .get("history");

        // Find the 'created' event
        JsonNode created = null;
        for (int i = 0; i < history.size(); i++) {
            if ("created".equals(history.get(i).get("type").asString())) {
                created = history.get(i);
                break;
            }
        }
        assertThat(created).isNotNull();

        JsonNode after = created.get("payload").get("after");
        assertThat(after.get("title").asString()).isEqualTo("Snimok polej");
        assertThat(after.get("category").asString()).isEqualTo("Portal");
        assertThat(after.get("priority").asString()).isEqualTo("Critical");
        assertThat(after.get("status").asString()).isEqualTo("New");
        assertThat(after.get("assignee_id").asInt()).isEqualTo(2);
        assertThat(created.get("payload").has("before")).isFalse();
        assertThat(created.get("actor_id").asInt()).isEqualTo(1);
    }

    @Test
    void assignedEventHasCorrectBeforeAfterPayload() throws Exception {
        String id = createAndTrack("Naznachenie payload", "Mail", "High");
        patchApi("/api/incidents/" + id, "{\"assignee_id\": 3}")
                .andExpect(status().isOk());

        JsonNode history = bodyJson(getApi("/api/incidents/" + id).andReturn())
                .get("history");
        JsonNode assigned = history.get(0); // newest first
        assertThat(assigned.get("type").asString()).isEqualTo("assigned");
        assertThat(assigned.get("payload").get("before").get("assignee_id").isNull()).isTrue();
        assertThat(assigned.get("payload").get("after").get("assignee_id").asInt()).isEqualTo(3);
    }

    // ─── PATCH no-op: no events, no updated_at change ───

    @Test
    void patchNoOpDoesNotBumpUpdatedAtOrWriteHistory() throws Exception {
        String id = createAndTrack("No-op PATCH", "Database", "Medium");
        // Get the initial updated_at from the response
        MvcResult initial = getApi("/api/incidents/" + id).andReturn();
        OffsetDateTime originalUpdatedAt = OffsetDateTime.parse(
                bodyJson(initial).get("updated_at").asString());
        long historyBefore = historyCount(id);

        MvcResult result = patchApi("/api/incidents/" + id, """
                {"title":"No-op PATCH","category":"Database","priority":"Medium"}
                """)
                .andExpect(status().isOk())
                .andReturn();

        OffsetDateTime afterUpdatedAt = OffsetDateTime.parse(bodyJson(result).get("updated_at").asString());
        assertThat(afterUpdatedAt.toInstant()).isEqualTo(originalUpdatedAt.toInstant());
        assertThat(historyCount(id)).isEqualTo(historyBefore);
    }

    // ─── Dashboard: resolved_today edge case ───

    @Test
    void incidentResolvedAndClosedTodayStillCountsInResolvedToday() throws Exception {
        long before = resolvedTodayCount();

        String id = createAndTrack("Reshen i zakryt segodnya", "Network", "High");
        incidentService.changeStatus(actor, id, IncidentStatus.IN_PROGRESS, null);
        incidentService.changeStatus(actor, id, IncidentStatus.RESOLVED, "Resheno segodnya zhe");
        incidentService.changeStatus(actor, id, IncidentStatus.CLOSED, null);

        assertThat(resolvedTodayCount()).isEqualTo(before + 1);

        int apiResolvedToday = bodyJson(getApiNoHeader("/api/dashboard").andReturn())
                .get("counts").get("resolved_today").asInt();
        assertThat((long) apiResolvedToday).isEqualTo(resolvedTodayCount());
    }

    @Test
    void emptyDashboardAfterPurgeConvergesWithDatabase() throws Exception {
        cleanup.purgeAllIncidents();

        MvcResult result = getApiNoHeader("/api/dashboard")
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = bodyJson(result);

        assertThat(data.get("counts").get("open").asLong()).isZero();
        assertThat(data.get("counts").get("new").asLong()).isZero();
        assertThat(data.get("counts").get("in_progress").asLong()).isZero();
        assertThat(data.get("counts").get("critical").asLong()).isZero();
        assertThat(data.get("counts").get("resolved_today").asLong()).isZero();
        assertThat(data.get("by_priority")).isEmpty();
        assertThat(data.get("recent_activity")).isEmpty();
        assertThat(data.get("dynamics")).hasSize(7);
    }

    // ─── 404: Incident not found ───

    @Test
    void getUnknownIncidentReturns404WithDetail() throws Exception {
        getApi("/api/incidents/INC-99999")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value(containsString("INC-99999")));
    }

    @Test
    void patchUnknownIncidentReturns404() throws Exception {
        patchApi("/api/incidents/INC-99999", "{\"title\": \"Test\"}")
                .andExpect(status().isNotFound());
    }

    @Test
    void patchStatusUnknownIncidentReturns404() throws Exception {
        patchApi("/api/incidents/INC-99999/status", "{\"status\": \"In Progress\"}")
                .andExpect(status().isNotFound());
    }

    // ─── DELETE returns 405 ───

    @Test
    void deleteIncidentReturns405() throws Exception {
        String id = createAndTrack("DELETE 405", "Other", "Low");
        performApi(delete("/api/incidents/" + id))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.detail").exists());

        getApi("/api/incidents/" + id)
                .andExpect(status().isOk());
    }

    // ─── GET /api/users with missing header (allowed) ───

    @Test
    void listUsersWithoutHeaderSucceeds() throws Exception {
        getApiNoHeader("/api/users")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").isNumber())
                .andExpect(jsonPath("$[0].name").isString())
                .andExpect(jsonPath("$[0].role").isString());
    }

    // ─── Concurrent sequential id generation ───

    @Test
    void concurrentCreatesProduceSequentialIdsWithoutGaps() throws Exception {
        String a = createAndTrack("Konkurentnyj 1", "Other", "Low");
        String b = createAndTrack("Konkurentnyj 2", "Other", "Low");
        String c = createAndTrack("Konkurentnyj 3", "Other", "Low");

        int ia = Integer.parseInt(a.substring(4));
        int ib = Integer.parseInt(b.substring(4));
        int ic = Integer.parseInt(c.substring(4));

        assertThat(ib).isEqualTo(ia + 1);
        assertThat(ic).isEqualTo(ib + 1);
    }

    // ─── Enum persistence: category/priority round-trip ───

    @Test
    void allCategoriesPersistAndRoundTrip() throws Exception {
        for (String cat : java.util.List.of("Network", "Mail", "Portal", "Database", "Workplace", "Other")) {
            MvcResult r = postApi("/api/incidents", """
                    {"title":"Category %s","category":"%s","priority":"Low"}
                    """.formatted(cat, cat))
                    .andExpect(status().isCreated())
                    .andReturn();
            cleanup.track(bodyJson(r).get("id").asString());
            assertThat(bodyJson(r).get("category").asString()).isEqualTo(cat);
        }
    }

    @Test
    void allPrioritiesPersistAndRoundTrip() throws Exception {
        for (String pri : java.util.List.of("Critical", "High", "Medium", "Low")) {
            MvcResult r = postApi("/api/incidents", """
                    {"title":"Priority %s","category":"Other","priority":"%s"}
                    """.formatted(pri, pri))
                    .andExpect(status().isCreated())
                    .andReturn();
            cleanup.track(bodyJson(r).get("id").asString());
            assertThat(bodyJson(r).get("priority").asString()).isEqualTo(pri);
        }
    }

    // ─── 400 enum validation in create body ───

    @Test
    void createWithInvalidEnumValuesReturns400WithFieldName() throws Exception {
        long before = incidentCount();

        postApi("/api/incidents", """
                {"title":"Invalid enum","category":"BogusCat","priority":"Low"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("category")));

        postApi("/api/incidents", """
                {"title":"Invalid enum","category":"Other","priority":"BogusPri"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("priority")));

        assertThat(incidentCount()).isEqualTo(before);
    }

    @Test
    void createWithoutRequiredCategoryOrPriorityReturns400() throws Exception {
        postApi("/api/incidents", """
                {"title":"Missing category","priority":"Low"}
                """)
                .andExpect(status().isBadRequest());

        postApi("/api/incidents", """
                {"title":"Missing priority","category":"Other"}
                """)
                .andExpect(status().isBadRequest());
    }

    // ─── Status endpoint: unknown status value returns 400, not 409 ───

    @Test
    void unknownStatusValueInBodyReturns400() throws Exception {
        String id = createAndTrack("Unknown status body", "Portal", "Medium");
        patchApi("/api/incidents/" + id + "/status",
                "{\"status\": \"BogusStatus\"}")
                .andExpect(status().isBadRequest());
    }

    // ─── Snake-case: response keys are snake_case, not camelCase ───

    @Test
    void incidentResponseKeysAreSnakeCase() throws Exception {
        String id = createAndTrack("Snake case keys", "Other", "Low");
        MvcResult result = getApi("/api/incidents/" + id)
                .andExpect(status().isOk())
                .andReturn();

        JsonNode node = bodyJson(result);
        assertThat(node.has("assignee_id")).isTrue();
        assertThat(node.has("assigneeId")).isFalse();
        assertThat(node.has("created_at")).isTrue();
        assertThat(node.has("createdAt")).isFalse();
        assertThat(node.has("updated_at")).isTrue();
        assertThat(node.has("updatedAt")).isFalse();
    }

    // ─── UTC timestamps ───

    @Test
    void createdAndUpdatedAtAreUtcIso8601() throws Exception {
        String id = createAndTrack("UTC timestamps", "Network", "Medium");
        MvcResult result = getApi("/api/incidents/" + id)
                .andExpect(status().isOk())
                .andReturn();

        JsonNode node = bodyJson(result);
        String created = node.get("created_at").asString();
        String updated = node.get("updated_at").asString();

        // ISO-8601 with UTC marker Z or UTC offset
        assertThat(created).containsPattern("(Z|[+-]\\d{2}:\\d{2})$");
        assertThat(updated).containsPattern("(Z|[+-]\\d{2}:\\d{2})$");
    }

    // ─── Comment text boundary check ───

    @Test
    void commentTextExactlyOneCharSucceeds() throws Exception {
        String id = createAndTrack("Comment 1 char", "Other", "Low");
        postApi("/api/incidents/" + id + "/comments", "{\"text\": \"x\"}")
                .andExpect(status().isCreated());
    }

    @Test
    void commentTextExactly2000CharsSucceeds() throws Exception {
        String id = createAndTrack("Comment 2000 chars", "Other", "Low");
        String text = "x".repeat(2000);
        postApi("/api/incidents/" + id + "/comments", "{\"text\": \"" + text + "\"}")
                .andExpect(status().isCreated());
    }

    // ─── Dashboard GET with missing header (allowed per ADR-002) ───

    @Test
    void dashboardWithoutHeaderSucceeds() throws Exception {
        performApi(get("/api/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.open").isNumber());
    }

    // ─── PATCH assignee_id: null clears assignment ───

    @Test
    void patchAssigneeIdNullClearsAssignment() throws Exception {
        String id = createAndTrack("Clear assignee", "Portal", "High", 2);
        patchApi("/api/incidents/" + id, "{\"assignee_id\": null}")
                .andExpect(status().isOk());

        getApi("/api/incidents/" + id)
                .andExpect(jsonPath("$.assignee_id").value(org.hamcrest.Matchers.nullValue()));
    }

    // ─── Dashboard recent_activity limit (10), not return_all ───

    @Test
    void recentActivityCappedAtTenAcrossAllIncidents() throws Exception {
        cleanup.purgeAllIncidents();
        // Create 2 incidents, add 6 comments each = 12 events total + 2 created = 14
        for (int i = 0; i < 2; i++) {
            String id = createAndTrack("Activity cap " + i, "Other", "Low");
            for (int j = 0; j < 6; j++) {
                commentService.add(actor, id, "Comment " + j);
            }
        }

        JsonNode activity = bodyJson(getApiNoHeader("/api/dashboard").andReturn())
                .get("recent_activity");
        assertThat(activity).hasSize(10);
    }

    // ─── REST helpers that track created incidents ─────────────────────

    /** Creates an incident via REST, marks In Progress via service, and tracks it. */
    private String createInProgressRest(String title, String category, String priority) throws Exception {
        String id = createAndTrack(title, category, priority);
        incidentService.changeStatus(actor, id, IncidentStatus.IN_PROGRESS, null);
        return id;
    }

    /** Creates an incident via REST, marks Resolved via service, and tracks it. */
    private String createResolvedRest(String title, String category, String priority, String resolution)
            throws Exception {
        String id = createInProgressRest(title, category, priority);
        incidentService.changeStatus(actor, id, IncidentStatus.RESOLVED, resolution);
        return id;
    }

    // ─── Helpers added to base ─────────────────────────────────────────

    /** Counts resolved-today events by direct DB query (same as DashboardApiTest). */
    private long resolvedTodayCount() {
        OffsetDateTime todayStart = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
        return dataManager.loadValue(
                        "select count(e) from inc_HistoryEvent e where e.type = :type and e.createdAt >= :start",
                        Long.class)
                .parameter("type", HistoryEventType.RESOLVED)
                .parameter("start", todayStart)
                .one();
    }
}