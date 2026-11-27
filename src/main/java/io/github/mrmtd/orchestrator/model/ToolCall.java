package io.github.mrmtd.orchestrator.model;

/** One MCP tool call the model made while answering. {@code outcome} is {@code success} or {@code error}. */
public record ToolCall(String tool, String arguments, String outcome, long durationMs) {
}
