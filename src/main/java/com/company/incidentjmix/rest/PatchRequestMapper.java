package com.company.incidentjmix.rest;

import com.company.incidentjmix.service.PatchCommand;
import com.company.incidentjmix.service.ValidationException;
import tools.jackson.databind.JsonNode;

/**
 * Transport mapping of a PATCH /api/incidents/{id} JSON body into a
 * {@link PatchCommand} (ADR-002). Distinguishes absent fields (unchanged)
 * from explicit null (clear / unassign) and rejects wrong JSON types with
 * a field-named 400. Business validation stays in IncidentService.
 */
public final class PatchRequestMapper {

    private PatchRequestMapper() {
    }

    public static PatchCommand toPatchCommand(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new ValidationException("body", "тело запроса должно быть JSON-объектом");
        }
        PatchCommand command = new PatchCommand();
        applyStringField(body, "title", command::title);
        applyStringField(body, "description", command::description);
        applyStringField(body, "category", command::category);
        applyStringField(body, "priority", command::priority);
        if (body.has("assignee_id")) {
            JsonNode node = body.get("assignee_id");
            if (node.isNull()) {
                command.assigneeId(null);
            } else if (node.isIntegralNumber()) {
                command.assigneeId(node.intValue());
            } else {
                throw new ValidationException("assignee_id", "должно быть целым числом или null");
            }
        }
        return command;
    }

    private static void applyStringField(JsonNode body, String field, java.util.function.Consumer<String> setter) {
        if (body.has(field)) {
            JsonNode node = body.get(field);
            if (node.isNull()) {
                setter.accept(null);
            } else if (node.isTextual()) {
                setter.accept(node.asString());
            } else {
                throw new ValidationException(field, "должно быть строкой");
            }
        }
    }
}
