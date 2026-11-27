package io.github.mrmtd.orchestrator;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The orchestrator starts without the MCP server, and answers 503 until it is reachable. */
@SpringBootTest(properties = {
        "orchestrator.mcp.base-url=http://localhost:1",
        "orchestrator.mcp.connect-timeout=1s",
        "orchestrator.mcp.initialization-timeout=2s"})
@AutoConfigureMockMvc
class McpServerDownTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    MeterRegistry meters;

    @Test
    void apiAnswers503() throws Exception {
        mvc.perform(get("/api/v1/loads/420"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("mcp_unavailable"));

        assertThat(meters.get("orchestrator.mcp.connect").tags("outcome", "failure").timer().count()).isPositive();
        assertThat(meters.get("orchestrator.mcp.connected").gauge().value()).isZero();
    }

    @Test
    void healthIsDown() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.components.mcpServer.status").value("DOWN"));
    }
}
