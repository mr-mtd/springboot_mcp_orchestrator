package io.github.mrmtd.orchestrator.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.Nullable;

import java.time.OffsetDateTime;

/** Current state of one load, returned by the getLoadStatus MCP tool (same shape as the transport MCP server returns). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LoadStatus(
        String loadNumber,
        String status,
        @Nullable Long carrierId,
        @Nullable String carrierName,
        String equipmentType,
        String originCity,
        String originState,
        String destinationCity,
        String destinationState,
        @Nullable Integer miles,
        OffsetDateTime pickupScheduledAt,
        @Nullable OffsetDateTime pickedUpAt,
        OffsetDateTime deliveryScheduledAt,
        @Nullable OffsetDateTime deliveredAt,
        /** Hours past the scheduled delivery (negative means early or still ahead of schedule); null when not applicable. */
        @Nullable Double hoursLate,
        /** True when the load is, or was delivered, more than the lateness threshold behind schedule. */
        boolean late,
        @Nullable LoadEventSummary lastEvent) {
}
