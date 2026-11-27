package io.github.mrmtd.orchestrator.api;

import io.github.mrmtd.orchestrator.mcp.ToolInfo;
import io.github.mrmtd.orchestrator.model.CarrierDetails;
import io.github.mrmtd.orchestrator.model.DelayDashboard;
import io.github.mrmtd.orchestrator.model.DelayedShipmentQuery;
import io.github.mrmtd.orchestrator.model.DelayedShipments;
import io.github.mrmtd.orchestrator.model.LoadOverview;
import io.github.mrmtd.orchestrator.model.LoadStatus;
import io.github.mrmtd.orchestrator.service.TransportOrchestrator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The REST API the React front end calls. Input is checked here first (400 without an MCP call),
 * and the MCP server checks it again. Errors are RFC 9457 problem details, see {@link ApiExceptionHandler}.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Transport", description = "Freight data from the transport MCP server")
public class TransportController {

    private final TransportOrchestrator orchestrator;

    public TransportController(TransportOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @GetMapping("/loads/{loadNumber}")
    @Operation(summary = "Load status", description = "Calls the getLoadStatus MCP tool.")
    public LoadStatus load(@Parameter(description = "Load number, e.g. LD-000420 or 420")
                           @PathVariable @NotBlank String loadNumber) {
        return orchestrator.loadStatus(loadNumber);
    }

    @GetMapping("/loads/{loadNumber}/overview")
    @Operation(summary = "Load with its carrier",
            description = "getLoadStatus, then getCarrierDetails for the load's carrier. If the carrier lookup "
                    + "fails the load is still returned, with a warning.")
    public LoadOverview loadOverview(@PathVariable @NotBlank String loadNumber) {
        return orchestrator.loadOverview(loadNumber);
    }

    @GetMapping("/carriers/{carrierId}")
    @Operation(summary = "Carrier by id", description = "Calls the getCarrierDetails MCP tool.")
    public CarrierDetails carrier(@PathVariable @Positive long carrierId) {
        return orchestrator.carrierById(carrierId);
    }

    @GetMapping("/carriers")
    @Operation(summary = "Carrier by MC number", description = "Calls the getCarrierDetails MCP tool.")
    public CarrierDetails carrierByMcNumber(@Parameter(description = "FMCSA MC number, e.g. MC155433")
                                            @RequestParam @NotBlank String mcNumber) {
        return orchestrator.carrierByMcNumber(mcNumber);
    }

    @GetMapping("/shipments/delayed")
    @Operation(summary = "Delayed shipments", description = "Calls the findDelayedShipments MCP tool.")
    public DelayedShipments delayedShipments(
            @RequestParam(required = false) @DecimalMin("0") Double minHoursLate,
            @RequestParam(required = false) @Positive Long carrierId,
            @RequestParam(required = false) @Pattern(regexp = "[A-Za-z]{2}") String originState,
            @RequestParam(required = false) @Pattern(regexp = "[A-Za-z]{2}") String destinationState,
            @RequestParam(required = false) Boolean includeDelivered,
            @RequestParam(required = false) @Min(1) @Max(100) Integer limit) {
        return orchestrator.delayedShipments(new DelayedShipmentQuery(
                minHoursLate, carrierId, originState, destinationState, includeDelivered, limit));
    }

    @GetMapping("/dashboard/delays")
    @Operation(summary = "Delay dashboard",
            description = "findDelayedShipments plus totals, counts by state, and the carriers with the most "
                    + "delayed loads, each looked up with getCarrierDetails in parallel.")
    public DelayDashboard delayDashboard(
            @RequestParam(required = false) @DecimalMin("0") Double minHoursLate,
            @RequestParam(required = false) @Pattern(regexp = "[A-Za-z]{2}") String originState,
            @RequestParam(required = false) @Pattern(regexp = "[A-Za-z]{2}") String destinationState,
            @RequestParam(required = false) Boolean includeDelivered,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit,
            @Parameter(description = "How many carriers to look up, 0 to 10")
            @RequestParam(defaultValue = "5") @Min(0) @Max(TransportOrchestrator.MAX_TOP_CARRIERS) int topCarriers) {
        return orchestrator.delayDashboard(new DelayedShipmentQuery(
                minHoursLate, null, originState, destinationState, includeDelivered, limit), topCarriers);
    }

    @GetMapping("/tools")
    @Operation(summary = "MCP tools", description = "The tools the MCP server lists (tools/list).")
    public List<ToolInfo> tools() {
        return orchestrator.listTools();
    }
}
