package io.github.mrmtd.orchestrator.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** One row of load_event, as shown inside tool results. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LoadEventSummary(
        String eventType,
        OffsetDateTime occurredAt,
        @Nullable String city,
        @Nullable String state,
        @Nullable BigDecimal latitude,
        @Nullable BigDecimal longitude,
        @Nullable String delayReason,
        @Nullable String notes) {
}
