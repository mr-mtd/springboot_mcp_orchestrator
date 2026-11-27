package io.github.mrmtd.orchestrator;

import io.github.mrmtd.orchestrator.support.FakeTransportMcpServer;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The whole orchestrator against a real MCP server over Streamable HTTP: the REST API, the MCP
 * client and session handling, JSON mapping, error mapping, metrics and health.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrchestratorIntegrationTest {

    static final FakeTransportMcpServer mcp = new FakeTransportMcpServer();

    @DynamicPropertySource
    static void mcpServer(DynamicPropertyRegistry registry) {
        registry.add("orchestrator.mcp.base-url", mcp::baseUrl);
    }

    @AfterAll
    static void stopServer() {
        mcp.close();
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    MeterRegistry meters;

    @BeforeEach
    void reset() {
        mcp.resetCalls();
    }

    @Test
    void getsLoadStatusThroughMcp() throws Exception {
        mvc.perform(get("/api/v1/loads/420"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loadNumber").value("LD-000420"))
                .andExpect(jsonPath("$.carrierId").value(7))
                .andExpect(jsonPath("$.late").value(true))
                .andExpect(jsonPath("$.lastEvent.delayReason").value("WEATHER"))
                .andExpect(header().exists("X-Request-Id"));

        assertThat(mcp.calls("getLoadStatus")).isEqualTo(1);
        assertThat(meters.get("orchestrator.mcp.tool.calls")
                .tags("tool", "getLoadStatus", "outcome", "success").timer().count()).isPositive();
        assertThat(meters.get("orchestrator.mcp.connected").gauge().value()).isEqualTo(1.0);
    }

    @Test
    void unknownLoadIs404ProblemDetail() throws Exception {
        mvc.perform(get("/api/v1/loads/LD-999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No load found with load number LD-999999"))
                .andExpect(jsonPath("$.code").value("not_found"))
                .andExpect(jsonPath("$.tool").value("getLoadStatus"));

        assertThat(meters.get("orchestrator.mcp.tool.failures")
                .tags("tool", "getLoadStatus", "outcome", "not_found").counter().count()).isPositive();
    }

    @Test
    void badInputFromTheServerIs400() throws Exception {
        mvc.perform(get("/api/v1/loads/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_input"));
    }

    @Test
    void serverRateLimitIs429WithRetryAfter() throws Exception {
        mvc.perform(get("/api/v1/loads/RATE"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "3"))
                .andExpect(jsonPath("$.code").value("rate_limited"));
    }

    @Test
    void getsCarrierByIdAndByMcNumber() throws Exception {
        mvc.perform(get("/api/v1/carriers/9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Lone Star Haulers"));
        mvc.perform(get("/api/v1/carriers").param("mcNumber", "MC155433"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7));
    }

    @Test
    void loadOverviewJoinsLoadAndCarrier() throws Exception {
        mvc.perform(get("/api/v1/loads/LD-000420/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.load.loadNumber").value("LD-000420"))
                .andExpect(jsonPath("$.carrier.name").value("Swift Freight"))
                .andExpect(jsonPath("$.warnings", hasSize(0)));

        assertThat(mcp.calls("getLoadStatus")).isEqualTo(1);
        assertThat(mcp.calls("getCarrierDetails")).isEqualTo(1);
    }

    @Test
    void loadOverviewWithoutCarrierMakesOneCall() throws Exception {
        mvc.perform(get("/api/v1/loads/500/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.carrier").doesNotExist());
        assertThat(mcp.calls("getCarrierDetails")).isZero();
    }

    @Test
    void delayDashboardAggregatesAndToleratesAFailedCarrierLookup() throws Exception {
        mvc.perform(get("/api/v1/dashboard/delays").param("topCarriers", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalDelayed").value(4))
                .andExpect(jsonPath("$.maxHoursLate").value(12.0))
                .andExpect(jsonPath("$.avgHoursLate").value(7.3))
                .andExpect(jsonPath("$.byOriginState.TX").value(2))
                .andExpect(jsonPath("$.byDestinationState.GA").value(2))
                // carrier 7 has two delayed loads, so it comes first
                .andExpect(jsonPath("$.topCarriers[0].carrierId").value(7))
                .andExpect(jsonPath("$.topCarriers[0].delayedLoads").value(2))
                .andExpect(jsonPath("$.topCarriers[0].avgHoursLate").value(7.0))
                .andExpect(jsonPath("$.topCarriers[0].onTimePctLast90Days").value(92.5))
                .andExpect(jsonPath("$.topCarriers[*].carrierId", containsInAnyOrder(7, 9, 11)))
                // carrier 11 has no profile on the server: listed without it, with a warning
                .andExpect(jsonPath("$.warnings", hasSize(1)))
                .andExpect(jsonPath("$.warnings[0]").value("Carrier 11 details unavailable: No carrier found with id 11"));

        assertThat(mcp.calls("findDelayedShipments")).isEqualTo(1);
        assertThat(mcp.calls("getCarrierDetails")).isEqualTo(3);
        assertThat(meters.get("orchestrator.operations")
                .tags("operation", "delayDashboard", "outcome", "partial").timer().count()).isPositive();
        assertThat(meters.get("orchestrator.dashboard.carrier.lookups")
                .tags("outcome", "not_found").counter().count()).isPositive();
    }

    @Test
    void passesFiltersThrough() throws Exception {
        mvc.perform(get("/api/v1/shipments/delayed").param("limit", "2").param("includeDelivered", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.includeDelivered").value(true));
    }

    @Test
    void listsTheServersTools() throws Exception {
        mvc.perform(get("/api/v1/tools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name",
                        containsInAnyOrder("getLoadStatus", "getCarrierDetails", "findDelayedShipments")))
                .andExpect(jsonPath("$[0].readOnly").value(true));
    }

    @Test
    void healthIncludesTheMcpServer() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.mcpServer.status").value("UP"));
    }

    @Test
    void exposesPrometheusMetrics() throws Exception {
        mvc.perform(get("/api/v1/loads/420")).andExpect(status().isOk());
        mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("orchestrator_mcp_tool_calls_seconds_bucket")
                        .contains("orchestrator_operations_seconds_count"));
    }
}
