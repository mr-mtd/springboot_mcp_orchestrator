package io.github.mrmtd.orchestrator.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.Nullable;

import java.time.OffsetDateTime;

/** One late load, as listed by the findDelayedShipments MCP tool (same shape as the transport MCP server returns). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DelayedShipment(
        String loadNumber,
        String status,
        Long carrierId,
        String carrierName,
        String originCity,
        String originState,
        String destinationCity,
        String destinationState,
        OffsetDateTime deliveryScheduledAt,
        @Nullable OffsetDateTime deliveredAt,
        double hoursLate,
        /** Reason from the most recent DELAY_REPORTED event, if the driver reported one. */
        @Nullable String latestDelayReason) {
}
