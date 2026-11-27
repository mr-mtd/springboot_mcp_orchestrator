package io.github.mrmtd.orchestrator.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.Nullable;

/** A carrier plus its recent load activity, returned by the getCarrierDetails MCP tool (same shape as the transport MCP server returns). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CarrierDetails(
        long id,
        String name,
        String mcNumber,
        String dotNumber,
        @Nullable String scac,
        @Nullable String contactEmail,
        @Nullable String phone,
        @Nullable String homeState,
        String safetyRating,
        boolean active,
        /** Loads assigned to the carrier and not yet delivered (ASSIGNED or IN_TRANSIT). */
        int openLoads,
        /** Loads delivered in the last 90 days. */
        int deliveredLast90Days,
        /** Of those, how many arrived more than 2 hours after the scheduled delivery. */
        int lateDeliveriesLast90Days,
        /** Share delivered on time in the last 90 days, 0-100; null when nothing was delivered. */
        @Nullable Double onTimePctLast90Days) {
}
