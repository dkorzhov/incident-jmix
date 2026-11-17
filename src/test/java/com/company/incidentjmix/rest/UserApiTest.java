package com.company.incidentjmix.rest;

import com.company.incidentjmix.entity.AppUser;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /api/users (TASK.md §3, ADR-002; AC-USERS-1…3). */
class UserApiTest extends AbstractRestApiTest {

    @Test
    void usersReturnsPreloadedListSortedByIdWithoutHeader() throws Exception {
        MvcResult result = getApiNoHeader("/api/users")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].name").exists())
                .andExpect(jsonPath("$[0].role").exists())
                .andReturn();

        JsonNode array = bodyJson(result);
        List<Integer> ids = new ArrayList<>();
        array.forEach(node -> ids.add(node.get("id").asInt()));
        assertThat(ids).containsExactly(1, 2, 3, 4);

        // converges with the domain user list; only id/name/role fields
        List<AppUser> users = appUserService.list();
        for (int i = 0; i < users.size(); i++) {
            JsonNode node = array.get(i);
            assertThat(node.get("name").asString()).isEqualTo(users.get(i).getName());
            assertThat(node.get("role").asString()).isEqualTo(users.get(i).getRole().getId());
            assertThat(node.size()).isEqualTo(3);
        }
    }

    @Test
    void usersWithValidHeaderReturns200() throws Exception {
        getApi("/api/users")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4));
    }

    @Test
    void usersWithUnknownHeaderReturns401() throws Exception {
        performApi(get("/api/users").header("X-User-Id", "999"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").exists());
    }
}
