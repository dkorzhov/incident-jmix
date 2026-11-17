package com.company.incidentjmix.rest;

import com.company.incidentjmix.entity.Incident;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /api/incidents/{id}/comments (TASK.md §3, ADR-002; AC-COMMENT-1…5).
 * Author is always the X-User-Id header user, never a body field.
 */
class CommentApiTest extends AbstractRestApiTest {

    @Test
    void addCommentReturns201WithHeaderAuthorAndCommentedEvent() throws Exception {
        Incident incident = createIncident("Для комментариев", "Mail", "Low", null);
        String id = incident.getId();

        MvcResult result = performApi(post("/api/incidents/" + id + "/comments")
                        .header("X-User-Id", "2")
                        .contentType("application/json")
                        .content("""
                                {"text": "  Текст комментария  ", "author_id": 4, "id": 999}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.incident_id").value(id))
                .andExpect(jsonPath("$.author_id").value(2))
                .andExpect(jsonPath("$.text").value("Текст комментария"))
                .andExpect(jsonPath("$.created_at").exists())
                .andReturn();

        int commentId = bodyJson(result).get("id").asInt();

        // commented event with payload.after.comment_id/text; actor = header user
        MvcResult card = getApi("/api/incidents/" + id)
                .andExpect(jsonPath("$.history.length()").value(2))
                .andExpect(jsonPath("$.history[0].type").value("commented"))
                .andExpect(jsonPath("$.history[0].actor_id").value(2))
                .andExpect(jsonPath("$.history[0].payload.after.comment_id").value(commentId))
                .andExpect(jsonPath("$.history[0].payload.after.text").value("Текст комментария"))
                .andReturn();
        // comment does NOT bump incident.updated_at (compare instants, not strings)
        assertThat(OffsetDateTime.parse(bodyJson(card).get("updated_at").asString()).toInstant())
                .isEqualTo(incident.getUpdatedAt().toInstant());
    }

    @Test
    void commentValidationReturns400AndWritesNothing() throws Exception {
        Incident incident = createIncident("Валидация комментария", "Network", "Low", null);
        String id = incident.getId();
        long historyBefore = historyCount(id);

        patchComment(id, "{\"text\": \"\"}");
        patchComment(id, "{\"text\": \"   \"}");
        patchComment(id, "{}");
        patchComment(id, "{\"text\": null}");
        patchComment(id, "{\"text\": \"" + "x".repeat(2001) + "\"}");

        assertThat(historyCount(id)).isEqualTo(historyBefore);
        getApi("/api/incidents/" + id)
                .andExpect(jsonPath("$.comments").isEmpty());
    }

    @Test
    void commentOnClosedIncidentReturns409() throws Exception {
        Incident closed = createClosed("Закрыт для комментариев", "Other", "Low", "Решено");

        postApi("/api/incidents/" + closed.getId() + "/comments", "{\"text\": \"Поздний комментарий\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").exists());

        assertThat(historyCount(closed.getId())).isEqualTo(4);
    }

    @Test
    void commentOnUnknownIncidentReturns404() throws Exception {
        postApi("/api/incidents/INC-99999/comments", "{\"text\": \"Не найдено\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").exists());
    }

    @Test
    void commentWithoutValidHeaderReturns401BeforeAnyChange() throws Exception {
        Incident incident = createIncident("Комментарий без пользователя", "Network", "Low", null);
        String id = incident.getId();
        long historyBefore = historyCount(id);
        String body = "{\"text\": \"Текст\"}";

        performApi(post("/api/incidents/" + id + "/comments")
                        .contentType("application/json").content(body))
                .andExpect(status().isUnauthorized());
        performApi(post("/api/incidents/" + id + "/comments")
                        .header("X-User-Id", "abc")
                        .contentType("application/json").content(body))
                .andExpect(status().isUnauthorized());
        performApi(post("/api/incidents/" + id + "/comments")
                        .header("X-User-Id", "999")
                        .contentType("application/json").content(body))
                .andExpect(status().isUnauthorized());

        assertThat(historyCount(id)).isEqualTo(historyBefore);
    }

    @Test
    void commentsAreVisibleInCardInCreationOrder() throws Exception {
        Incident incident = createIncident("Порядок комментариев", "Portal", "Medium", null);
        String id = incident.getId();

        postApi("/api/incidents/" + id + "/comments", "{\"text\": \"Первый\"}")
                .andExpect(status().isCreated());
        postApi("/api/incidents/" + id + "/comments", "{\"text\": \"Второй\"}")
                .andExpect(status().isCreated());

        JsonNode comments = bodyJson(getApi("/api/incidents/" + id).andReturn()).get("comments");
        assertThat(comments).hasSize(2);
        assertThat(comments.get(0).get("text").asString()).isEqualTo("Первый");
        assertThat(comments.get(1).get("text").asString()).isEqualTo("Второй");
        assertThat(comments.get(0).get("id").asInt()).isLessThan(comments.get(1).get("id").asInt());
    }

    private void patchComment(String incidentId, String body) throws Exception {
        postApi("/api/incidents/" + incidentId + "/comments", body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("text")));
    }
}
