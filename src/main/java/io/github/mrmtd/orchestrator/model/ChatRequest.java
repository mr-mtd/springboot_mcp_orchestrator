package io.github.mrmtd.orchestrator.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * Body of {@code POST /api/v1/chat}.
 *
 * @param message        what the user typed
 * @param conversationId the id from the previous reply, to continue that conversation; leave it out to start one
 */
public record ChatRequest(@NotBlank @Size(max = 4000) String message, @Nullable @Size(max = 100) String conversationId) {
}
