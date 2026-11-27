package io.github.mrmtd.orchestrator.model;

import io.swagger.v3.oas.annotations.media.Schema;

/** One MCP tool call the model made while answering. {@code outcome} is {@code success} or {@code error}. */
public record ToolCall(
        @Schema(example = "findDelayedShipments") String tool,
        @Schema(description = "Arguments the model passed, as JSON", example = "{\"originState\":\"TX\"}") String arguments,
        @Schema(allowableValues = {"success", "error"}) String outcome,
        long durationMs) {
}
