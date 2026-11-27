package io.github.mrmtd.orchestrator.model;

import org.jspecify.annotations.Nullable;

import java.util.List;

/** Response of {@code POST /api/v1/chat}: the answer, the tool calls behind it, and token usage. */
public record ChatReply(String conversationId, String answer, @Nullable String model,
                        List<ToolCall> toolCalls, TokenUsage usage, long durationMs) {
}
