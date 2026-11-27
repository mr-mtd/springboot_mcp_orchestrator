package io.github.mrmtd.orchestrator.api;

import io.github.mrmtd.orchestrator.config.WebConfig;
import io.github.mrmtd.orchestrator.mcp.McpToolException;
import io.github.mrmtd.orchestrator.mcp.McpUnavailableException;
import io.github.mrmtd.orchestrator.service.TransportOrchestrator;
import io.github.mrmtd.orchestrator.web.CorrelationIdFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP behaviour of the API without an MCP server: validation, error mapping, CORS, request ids. */
@WebMvcTest(TransportController.class)
@Import({WebConfig.class, CorrelationIdFilter.class})
@EnableConfigurationProperties(io.github.mrmtd.orchestrator.config.CorsProperties.class)
class TransportControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    TransportOrchestrator orchestrator;

    @Test
    void invalidQueryIs400WithoutCallingMcp() throws Exception {
        mvc.perform(get("/api/v1/shipments/delayed").param("limit", "500"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/shipments/delayed").param("originState", "Texas"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/dashboard/delays").param("topCarriers", "11"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/carriers/abc"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/carriers"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(orchestrator);
    }

    @Test
    void mapsToolErrorsToStatusCodes() throws Exception {
        when(orchestrator.loadStatus("1")).thenThrow(
                new McpToolException("getLoadStatus", McpToolException.Kind.NOT_FOUND, "No load found", 0));
        when(orchestrator.loadStatus("2")).thenThrow(
                new McpToolException("getLoadStatus", McpToolException.Kind.INVALID_INPUT, "bad", 0));
        when(orchestrator.loadStatus("3")).thenThrow(
                new McpToolException("getLoadStatus", McpToolException.Kind.RATE_LIMITED, "Rate limit", 4));
        when(orchestrator.loadStatus("4")).thenThrow(
                new McpUnavailableException("down", new RuntimeException("refused")));
        when(orchestrator.loadStatus("5")).thenThrow(new IllegalStateException("boom"));

        mvc.perform(get("/api/v1/loads/1")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:problem:orchestrator:not_found"));
        mvc.perform(get("/api/v1/loads/2")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/loads/3")).andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "4"));
        mvc.perform(get("/api/v1/loads/4")).andExpect(status().isServiceUnavailable())
                // the internal cause isn't leaked to the browser
                .andExpect(jsonPath("$.detail").value("The transport data service is unavailable. Try again shortly."));
        mvc.perform(get("/api/v1/loads/5")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("Unexpected error"));
    }

    @Test
    void corsPreflightAllowsTheReactDevServer() throws Exception {
        mvc.perform(options("/api/v1/loads/420")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"))
                .andExpect(header().exists(HttpHeaders.ACCESS_CONTROL_MAX_AGE));
    }

    @Test
    void corsExposesRequestIdAndRetryAfter() throws Exception {
        when(orchestrator.carrierById(7)).thenReturn(TransportOrchestratorFixtures.carrier());
        mvc.perform(get("/api/v1/carriers/7").header(HttpHeaders.ORIGIN, "http://localhost:3000"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,
                        org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.containsString("X-Request-Id"),
                                org.hamcrest.Matchers.containsString("Retry-After"))));
    }

    @Test
    void corsRejectsOtherOrigins() throws Exception {
        mvc.perform(options("/api/v1/loads/420")
                        .header(HttpHeaders.ORIGIN, "https://evil.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isForbidden());
    }

    @Test
    void echoesASafeRequestIdAndReplacesAnUnsafeOne() throws Exception {
        when(orchestrator.carrierById(anyLongValue())).thenReturn(TransportOrchestratorFixtures.carrier());
        mvc.perform(get("/api/v1/carriers/7").header("X-Request-Id", "abc-123"))
                .andExpect(header().string("X-Request-Id", "abc-123"));
        mvc.perform(get("/api/v1/carriers/7").header("X-Request-Id", "bad id\r\ninjected"))
                .andExpect(header().string("X-Request-Id", matchesPattern("[0-9a-f-]{36}")));
    }

    @Test
    void dashboardDefaults() throws Exception {
        when(orchestrator.delayDashboard(any(), anyInt())).thenReturn(null);
        mvc.perform(get("/api/v1/dashboard/delays")).andExpect(status().isOk());
        org.mockito.Mockito.verify(orchestrator).delayDashboard(
                org.mockito.ArgumentMatchers.argThat(q -> q.limit() == 50 && q.carrierId() == null),
                org.mockito.ArgumentMatchers.eq(5));
    }

    private static long anyLongValue() {
        return org.mockito.ArgumentMatchers.anyLong();
    }
}
