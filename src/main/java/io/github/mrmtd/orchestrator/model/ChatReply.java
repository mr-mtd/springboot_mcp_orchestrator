package io.github.mrmtd.orchestrator.model;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

import java.util.List;

/** Response of {@code POST /api/v1/chat}: the answer, the tool calls behind it, and token usage. */
public record ChatReply(
        @Schema(description = "Send this back with the next message to continue the conversation") String conversationId,
        @Schema(description = "The model's answer") String answer,
        @Schema(description = "Model that answered", example = "gpt-5-mini") @Nullable String model,
        @Schema(description = "MCP tool calls the model made, in order") List<ToolCall> toolCalls,
        TokenUsage usage,
        @Schema(description = "Time to answer, in milliseconds") long durationMs) {
}
