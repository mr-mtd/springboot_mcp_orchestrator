package io.github.mrmtd.orchestrator.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.Nullable;

/**
 * One carrier's share of the delayed shipments on the dashboard, joined with its profile. Profile
 * fields are null when the carrier wasn't among the top carriers looked up, or its lookup failed.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CarrierDelaySummary(
        long carrierId,
        String carrierName,
        int delayedLoads,
        double avgHoursLate,
        double maxHoursLate,
        @Nullable String safetyRating,
        @Nullable Integer openLoads,
        @Nullable Double onTimePctLast90Days) {
}
