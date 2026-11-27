package io.github.mrmtd.orchestrator.service;

import io.github.mrmtd.orchestrator.config.ExecutorConfig;
import io.github.mrmtd.orchestrator.mcp.McpToolClient;
import io.github.mrmtd.orchestrator.mcp.McpToolException;
import io.github.mrmtd.orchestrator.mcp.ToolInfo;
import io.github.mrmtd.orchestrator.model.CarrierDelaySummary;
import io.github.mrmtd.orchestrator.model.CarrierDetails;
import io.github.mrmtd.orchestrator.model.DelayDashboard;
import io.github.mrmtd.orchestrator.model.DelayedShipment;
import io.github.mrmtd.orchestrator.model.DelayedShipmentQuery;
import io.github.mrmtd.orchestrator.model.DelayedShipments;
import io.github.mrmtd.orchestrator.model.LoadOverview;
import io.github.mrmtd.orchestrator.model.LoadStatus;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.DoubleSummaryStatistics;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * What the REST API does, in terms of MCP tool calls. Single-tool operations pass straight through;
 * {@link #loadOverview} and {@link #delayDashboard} call several tools and combine the results.
 *
 * <p>Every operation is timed as {@code orchestrator.operations} with tags {@code operation} and
 * {@code outcome} ({@code success}, {@code partial} when some secondary lookups failed, or
 * {@code failure}), and logs one INFO line with its duration and how many MCP calls it made.
 * The dashboard also records {@code orchestrator.dashboard.delayed.shipments} (shipments per
 * dashboard) and {@code orchestrator.dashboard.carrier.lookups} (carrier lookups, tag {@code outcome}).
 */
@Service
public class TransportOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(TransportOrchestrator.class);

    static final String OPERATIONS = "orchestrator.operations";

    public static final String GET_LOAD_STATUS = "getLoadStatus";
    public static final String GET_CARRIER_DETAILS = "getCarrierDetails";
    public static final String FIND_DELAYED_SHIPMENTS = "findDelayedShipments";

    /** Most carriers the dashboard looks up; each is one MCP call against the server's rate limit. */
    public static final int MAX_TOP_CARRIERS = 10;

    private final McpToolClient tools;
    private final ExecutorService fanOut;
    private final MeterRegistry meters;
    private final Clock clock;

    public TransportOrchestrator(McpToolClient tools,
                                 @Qualifier(ExecutorConfig.FAN_OUT_EXECUTOR) ExecutorService fanOut,
                                 MeterRegistry meters,
                                 ObjectProvider<Clock> clock) {
        this.tools = tools;
        this.fanOut = fanOut;
        this.meters = meters;
        this.clock = clock.getIfAvailable(Clock::systemUTC);
    }

    public LoadStatus loadStatus(String loadNumber) {
        return timed("loadStatus", () -> new Outcome<>(
                tools.call(GET_LOAD_STATUS, Map.of("loadNumber", loadNumber), LoadStatus.class), 1, false));
    }

    public CarrierDetails carrierById(long carrierId) {
        return timed("carrierById", () -> new Outcome<>(carrier(carrierId), 1, false));
    }

    public CarrierDetails carrierByMcNumber(String mcNumber) {
        return timed("carrierByMcNumber", () -> new Outcome<>(
                tools.call(GET_CARRIER_DETAILS, Map.of("mcNumber", mcNumber), CarrierDetails.class), 1, false));
    }

    public DelayedShipments delayedShipments(DelayedShipmentQuery query) {
        return timed("delayedShipments", () -> new Outcome<>(
                tools.call(FIND_DELAYED_SHIPMENTS, query.toArguments(), DelayedShipments.class), 1, false));
    }

    public List<ToolInfo> listTools() {
        return timed("listTools", () -> new Outcome<>(tools.listTools(), 1, false));
    }

    /**
     * The load plus its carrier. If the carrier lookup fails the load is still returned, with a
     * warning, so the page can render what it has.
     */
    public LoadOverview loadOverview(String loadNumber) {
        return timed("loadOverview", () -> {
            LoadStatus load = tools.call(GET_LOAD_STATUS, Map.of("loadNumber", loadNumber), LoadStatus.class);
            if (load.carrierId() == null) {
                return new Outcome<>(new LoadOverview(load, null, List.of()), 1, false);
            }
            try {
                return new Outcome<>(new LoadOverview(load, carrier(load.carrierId()), List.of()), 2, false);
            } catch (RuntimeException e) {
                String warning = "Carrier " + load.carrierId() + " details unavailable: " + e.getMessage();
                log.warn("loadOverview partial loadNumber={} carrierId={} cause=\"{}\"",
                        load.loadNumber(), load.carrierId(), e.getMessage());
                return new Outcome<>(new LoadOverview(load, null, List.of(warning)), 2, true);
            }
        });
    }

    /**
     * Delayed shipments with totals, counts by state, and the {@code topCarriers} carriers with the
     * most delayed loads, each joined with its profile. The carrier lookups run in parallel; any that
     * fail are reported in {@code warnings} and the carrier is listed without its profile.
     */
    public DelayDashboard delayDashboard(DelayedShipmentQuery query, int topCarriers) {
        int top = Math.clamp(topCarriers, 0, MAX_TOP_CARRIERS);
        return timed("delayDashboard", () -> {
            DelayedShipments delayed = tools.call(FIND_DELAYED_SHIPMENTS, query.toArguments(), DelayedShipments.class);
            List<DelayedShipment> shipments = delayed.shipments() == null ? List.of() : delayed.shipments();

            // Group by carrier, most delayed loads first, then worst average.
            Map<Long, List<DelayedShipment>> byCarrier = shipments.stream()
                    .filter(s -> s.carrierId() != null)
                    .collect(Collectors.groupingBy(DelayedShipment::carrierId, LinkedHashMap::new, Collectors.toList()));
            List<Map.Entry<Long, List<DelayedShipment>>> ranked = byCarrier.entrySet().stream()
                    .sorted(Comparator.<Map.Entry<Long, List<DelayedShipment>>>comparingInt(e -> e.getValue().size())
                            .reversed()
                            .thenComparing(e -> -stats(e.getValue()).getAverage()))
                    .limit(top)
                    .toList();

            // Look the top carriers up in parallel.
            Map<Long, CompletableFuture<CarrierDetails>> lookups = new LinkedHashMap<>();
            for (var entry : ranked) {
                lookups.put(entry.getKey(), CompletableFuture.supplyAsync(() -> carrier(entry.getKey()), fanOut));
            }
            List<String> warnings = Collections.synchronizedList(new ArrayList<>());
            Map<Long, CarrierDetails> profiles = new HashMap<>();
            lookups.forEach((id, future) -> {
                try {
                    profiles.put(id, future.join());
                    countLookup("success");
                } catch (RuntimeException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    countLookup(cause instanceof McpToolException t ? t.kind().outcome() : "failure");
                    warnings.add("Carrier " + id + " details unavailable: " + cause.getMessage());
                    log.warn("delayDashboard carrier lookup failed carrierId={} cause=\"{}\"", id, cause.getMessage());
                }
            });

            List<CarrierDelaySummary> summaries = ranked.stream().map(e -> {
                DoubleSummaryStatistics s = stats(e.getValue());
                CarrierDetails p = profiles.get(e.getKey());
                return new CarrierDelaySummary(e.getKey(), e.getValue().getFirst().carrierName(), e.getValue().size(),
                        round1(s.getAverage()), round1(s.getMax()),
                        p == null ? null : p.safetyRating(),
                        p == null ? null : p.openLoads(),
                        p == null ? null : p.onTimePctLast90Days());
            }).toList();

            DoubleSummaryStatistics all = stats(shipments);
            DistributionSummary.builder("orchestrator.dashboard.delayed.shipments")
                    .description("Delayed shipments per dashboard response")
                    .register(meters).record(shipments.size());

            var dashboard = new DelayDashboard(
                    OffsetDateTime.now(clock),
                    delayed.minHoursLate(),
                    delayed.includeDelivered(),
                    shipments.size(),
                    shipments.isEmpty() ? 0 : round1(all.getAverage()),
                    shipments.isEmpty() ? 0 : round1(all.getMax()),
                    countBy(shipments, DelayedShipment::originState),
                    countBy(shipments, DelayedShipment::destinationState),
                    summaries,
                    shipments,
                    List.copyOf(warnings));
            log.info("delayDashboard built shipments={} carriers={} carrierLookups={} failedLookups={}",
                    shipments.size(), byCarrier.size(), lookups.size(), warnings.size());
            return new Outcome<>(dashboard, 1 + lookups.size(), !warnings.isEmpty());
        });
    }

    private CarrierDetails carrier(long carrierId) {
        return tools.call(GET_CARRIER_DETAILS, Map.of("carrierId", carrierId), CarrierDetails.class);
    }

    private void countLookup(String outcome) {
        meters.counter("orchestrator.dashboard.carrier.lookups", "outcome", outcome).increment();
    }

    /** Result of an operation plus what to log about it. */
    private record Outcome<T>(T value, int mcpCalls, boolean partial) {
    }

    private <T> T timed(String operation, Supplier<Outcome<T>> body) {
        long start = System.nanoTime();
        String outcome = "failure";
        int calls = 0;
        try {
            Outcome<T> result = body.get();
            outcome = result.partial() ? "partial" : "success";
            calls = result.mcpCalls();
            return result.value();
        } finally {
            long nanos = System.nanoTime() - start;
            Timer.builder(OPERATIONS)
                    .description("Orchestrator operations, each one or more MCP tool calls")
                    .tags("operation", operation, "outcome", outcome)
                    .publishPercentileHistogram()
                    .register(meters)
                    .record(nanos, TimeUnit.NANOSECONDS);
            if ("failure".equals(outcome)) {
                log.info("operation={} outcome=failure durationMs={}", operation, TimeUnit.NANOSECONDS.toMillis(nanos));
            } else {
                log.info("operation={} outcome={} mcpCalls={} durationMs={}",
                        operation, outcome, calls, TimeUnit.NANOSECONDS.toMillis(nanos));
            }
        }
    }

    private static DoubleSummaryStatistics stats(List<DelayedShipment> shipments) {
        return shipments.stream().mapToDouble(DelayedShipment::hoursLate).summaryStatistics();
    }

    private static Map<String, Long> countBy(List<DelayedShipment> shipments,
                                             java.util.function.Function<DelayedShipment, String> key) {
        return shipments.stream()
                .filter(s -> key.apply(s) != null)
                .collect(Collectors.groupingBy(key, TreeMap::new, Collectors.counting()));
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
