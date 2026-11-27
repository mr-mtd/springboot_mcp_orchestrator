package io.github.mrmtd.orchestrator.service;

import io.github.mrmtd.orchestrator.mcp.McpToolClient;
import io.github.mrmtd.orchestrator.mcp.McpToolException;
import io.github.mrmtd.orchestrator.mcp.McpUnavailableException;
import io.github.mrmtd.orchestrator.model.CarrierDetails;
import io.github.mrmtd.orchestrator.model.DelayedShipment;
import io.github.mrmtd.orchestrator.model.DelayedShipmentQuery;
import io.github.mrmtd.orchestrator.model.DelayedShipments;
import io.github.mrmtd.orchestrator.model.LoadStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TransportOrchestratorTest {

    static final Instant NOW = Instant.parse("2026-11-27T12:00:00Z");
    static final OffsetDateTime SCHEDULED = OffsetDateTime.parse("2026-11-26T18:00:00Z");

    McpToolClient tools = mock(McpToolClient.class);
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    TransportOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        var beans = new StaticListableBeanFactory(Map.of("clock", Clock.fixed(NOW, ZoneOffset.UTC)));
        orchestrator = new TransportOrchestrator(tools, executor, meters, beans.getBeanProvider(Clock.class));
    }

    @AfterEach
    void tearDown() {
        executor.close();
    }

    @Test
    void loadOverviewSkipsTheCarrierCallWhenThereIsNoCarrier() {
        when(tools.call(eq("getLoadStatus"), any(), eq(LoadStatus.class))).thenReturn(load(null));

        var overview = orchestrator.loadOverview("500");

        assertThat(overview.carrier()).isNull();
        assertThat(overview.warnings()).isEmpty();
        verify(tools, never()).call(eq("getCarrierDetails"), any(), any());
    }

    @Test
    void loadOverviewReturnsTheLoadWithAWarningWhenTheCarrierLookupFails() {
        when(tools.call(eq("getLoadStatus"), any(), eq(LoadStatus.class))).thenReturn(load(7L));
        when(tools.call(eq("getCarrierDetails"), eq(Map.of("carrierId", 7L)), eq(CarrierDetails.class)))
                .thenThrow(new McpUnavailableException("MCP call getCarrierDetails failed", new RuntimeException()));

        var overview = orchestrator.loadOverview("420");

        assertThat(overview.load().loadNumber()).isEqualTo("LD-000420");
        assertThat(overview.carrier()).isNull();
        assertThat(overview.warnings()).singleElement().asString().contains("Carrier 7 details unavailable");
        assertThat(meters.get("orchestrator.operations")
                .tags("operation", "loadOverview", "outcome", "partial").timer().count()).isEqualTo(1);
    }

    @Test
    void loadOverviewFailsWhenTheLoadLookupFails() {
        when(tools.call(eq("getLoadStatus"), any(), eq(LoadStatus.class)))
                .thenThrow(new McpToolException("getLoadStatus", McpToolException.Kind.NOT_FOUND, "No load found", 0));

        assertThatThrownBy(() -> orchestrator.loadOverview("1")).isInstanceOf(McpToolException.class);
        assertThat(meters.get("orchestrator.operations")
                .tags("operation", "loadOverview", "outcome", "failure").timer().count()).isEqualTo(1);
    }

    @Test
    void dashboardRanksCarriersAndOnlyLooksUpTheTopOnes() {
        when(tools.call(eq("findDelayedShipments"), any(), eq(DelayedShipments.class))).thenReturn(new DelayedShipments(
                2, false, 5, List.of(
                        shipment("LD-1", 3L, "TX", 3.0),
                        shipment("LD-2", 5L, "TX", 10.0),
                        shipment("LD-3", 5L, "OK", 6.0),
                        shipment("LD-4", 8L, "CA", 20.0),
                        shipment("LD-5", null, "CA", 4.0))));
        when(tools.call(eq("getCarrierDetails"), any(), eq(CarrierDetails.class)))
                .thenAnswer(inv -> carrier(((Number) ((Map<?, ?>) inv.getArgument(1)).get("carrierId")).longValue()));

        var dashboard = orchestrator.delayDashboard(new DelayedShipmentQuery(null, null, null, null, null, 50), 2);

        assertThat(dashboard.generatedAt()).isEqualTo(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        assertThat(dashboard.totalDelayed()).isEqualTo(5);
        assertThat(dashboard.avgHoursLate()).isEqualTo(8.6);
        assertThat(dashboard.maxHoursLate()).isEqualTo(20.0);
        assertThat(dashboard.byOriginState()).containsExactly(Map.entry("CA", 2L), Map.entry("OK", 1L), Map.entry("TX", 2L));
        // 5 has two loads; 8 and 3 have one each, and 8 is later on average, so it wins the tie.
        assertThat(dashboard.topCarriers()).extracting(c -> c.carrierId()).containsExactly(5L, 8L);
        assertThat(dashboard.topCarriers().getFirst().avgHoursLate()).isEqualTo(8.0);
        assertThat(dashboard.topCarriers().getFirst().safetyRating()).isEqualTo("SATISFACTORY");
        assertThat(dashboard.warnings()).isEmpty();
        verify(tools, never()).call(eq("getCarrierDetails"), eq(Map.of("carrierId", 3L)), any());
        assertThat(meters.get("orchestrator.dashboard.delayed.shipments").summary().totalAmount()).isEqualTo(5);
        assertThat(meters.get("orchestrator.dashboard.carrier.lookups").tags("outcome", "success").counter().count())
                .isEqualTo(2);
    }

    @Test
    void emptyDashboard() {
        when(tools.call(eq("findDelayedShipments"), any(), eq(DelayedShipments.class)))
                .thenReturn(new DelayedShipments(2, false, 0, List.of()));

        var dashboard = orchestrator.delayDashboard(new DelayedShipmentQuery(null, null, null, null, null, null), 5);

        assertThat(dashboard.totalDelayed()).isZero();
        assertThat(dashboard.avgHoursLate()).isZero();
        assertThat(dashboard.topCarriers()).isEmpty();
        assertThat(meters.get("orchestrator.operations")
                .tags("operation", "delayDashboard", "outcome", "success").timer().count()).isEqualTo(1);
    }

    @Test
    void topCarriersIsCappedAtTen() {
        List<DelayedShipment> many = java.util.stream.LongStream.rangeClosed(1, 15)
                .mapToObj(i -> shipment("LD-" + i, i, "TX", 5)).toList();
        when(tools.call(eq("findDelayedShipments"), any(), eq(DelayedShipments.class)))
                .thenReturn(new DelayedShipments(2, false, many.size(), many));
        when(tools.call(eq("getCarrierDetails"), any(), eq(CarrierDetails.class)))
                .thenAnswer(inv -> carrier(((Number) ((Map<?, ?>) inv.getArgument(1)).get("carrierId")).longValue()));

        var dashboard = orchestrator.delayDashboard(new DelayedShipmentQuery(null, null, null, null, null, null), 99);

        assertThat(dashboard.topCarriers()).hasSize(TransportOrchestrator.MAX_TOP_CARRIERS);
    }

    @Test
    void queryLeavesUnsetFiltersOut() {
        var args = new DelayedShipmentQuery(4.0, null, "TX", null, true, null).toArguments();
        assertThat(args).containsEntry("minHoursLate", 4.0).containsEntry("originState", "TX")
                .containsEntry("includeDelivered", true).containsEntry("limit", null);
    }

    static LoadStatus load(Long carrierId) {
        return new LoadStatus("LD-000420", "IN_TRANSIT", carrierId, carrierId == null ? null : "Carrier " + carrierId,
                "DRY_VAN", "Dallas", "TX", "Atlanta", "GA", 781, SCHEDULED.minusDays(1), null, SCHEDULED, null,
                3.0, true, null);
    }

    static DelayedShipment shipment(String loadNumber, Long carrierId, String originState, double hoursLate) {
        return new DelayedShipment(loadNumber, "IN_TRANSIT", carrierId, "Carrier " + carrierId, "City", originState,
                "Town", "GA", SCHEDULED, null, hoursLate, null);
    }

    static CarrierDetails carrier(long id) {
        return new CarrierDetails(id, "Carrier " + id, "MC" + id, "DOT" + id, null, null, null, "TX",
                "SATISFACTORY", true, 2, 30, 3, 90.0);
    }
}
