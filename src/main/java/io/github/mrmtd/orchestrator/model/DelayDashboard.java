package io.github.mrmtd.orchestrator.model;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Everything the front end's delay dashboard needs in one response: the delayed shipments, totals,
 * counts by origin and destination state, and the worst carriers with their profiles. Built from one
 * findDelayedShipments call plus getCarrierDetails for the top carriers, in parallel.
 *
 * @param warnings carrier lookups that failed; the rest of the dashboard is still filled in
 */
public record DelayDashboard(
        OffsetDateTime generatedAt,
        double minHoursLate,
        boolean includeDelivered,
        int totalDelayed,
        double avgHoursLate,
        double maxHoursLate,
        Map<String, Long> byOriginState,
        Map<String, Long> byDestinationState,
        List<CarrierDelaySummary> topCarriers,
        List<DelayedShipment> shipments,
        List<String> warnings) {
}
