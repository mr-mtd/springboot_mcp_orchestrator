package io.github.mrmtd.orchestrator.model;

import io.swagger.v3.oas.annotations.media.Schema;

/** Tokens used for one chat, summed over every model call in the tool loop. */
public record TokenUsage(
        @Schema(example = "812") int promptTokens,
        @Schema(example = "96") int completionTokens,
        @Schema(example = "908") int totalTokens) {
}
