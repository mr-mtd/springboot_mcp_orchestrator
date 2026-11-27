package io.github.mrmtd.orchestrator.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * A load together with its carrier, for the front end's load detail page. Built from getLoadStatus
 * and then getCarrierDetails.
 *
 * @param carrier  null when the load has no carrier yet, or the carrier lookup failed
 * @param warnings what couldn't be filled in, e.g. a failed carrier lookup
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LoadOverview(LoadStatus load, @Nullable CarrierDetails carrier, List<String> warnings) {
}
