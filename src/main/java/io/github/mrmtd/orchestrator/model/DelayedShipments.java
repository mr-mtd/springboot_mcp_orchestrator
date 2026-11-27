package io.github.mrmtd.orchestrator.model;

import java.util.List;

/**
 * Result of findDelayedShipments. Wrapped in an object (rather than a bare list) because an
 * MCP tool's structured output must be a JSON object.
 */
public record DelayedShipments(
        double minHoursLate,
        boolean includeDelivered,
        int count,
        List<DelayedShipment> shipments) {
}
