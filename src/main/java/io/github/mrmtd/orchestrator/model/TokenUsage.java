package io.github.mrmtd.orchestrator.model;

/** Tokens used for one chat, summed over every model call in the tool loop. */
public record TokenUsage(int promptTokens, int completionTokens, int totalTokens) {
}
