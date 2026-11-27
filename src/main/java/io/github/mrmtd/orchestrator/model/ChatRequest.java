package io.github.mrmtd.orchestrator.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code POST /api/v1/chat}.
 *
 * @param message        what the user typed
 * @param conversationId the id from the previous reply, to continue that conversation; leave it out to start one
 */
public record ChatRequest(
        @Schema(description = "What the user typed", example = "Which loads from Texas are running late?", maxLength = 4000)
        @NotBlank @Size(max = 4000) String message,
        @Schema(description = "From the previous reply, to continue that conversation; leave out to start a new one",
                example = "7a35d7b7-936b-41f9-addc-22ccb431565b", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Nullable @Size(max = 100) String conversationId) {
}
