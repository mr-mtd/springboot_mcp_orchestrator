package io.github.mrmtd.orchestrator.model;

import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/** Filters for findDelayedShipments. Nulls are left out so the MCP server applies its defaults. */
public record DelayedShipmentQuery(
        @Nullable Double minHoursLate,
        @Nullable Long carrierId,
        @Nullable String originState,
        @Nullable String destinationState,
        @Nullable Boolean includeDelivered,
        @Nullable Integer limit) {

    public Map<String, Object> toArguments() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("minHoursLate", minHoursLate);
        args.put("carrierId", carrierId);
        args.put("originState", originState);
        args.put("destinationState", destinationState);
        args.put("includeDelivered", includeDelivered);
        args.put("limit", limit);
        return args;
    }
}
