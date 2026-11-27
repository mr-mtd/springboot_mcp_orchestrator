package io.github.mrmtd.orchestrator;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The app starts without the MCP server, and /chat answers 503 until it's reachable. */
@SpringBootTest(properties = {
        "spring.ai.mcp.client.streamable-http.connections.transport.url=http://localhost:1",
        "spring.ai.mcp.client.request-timeout=2s",
        "spring.ai.openai.api-key=test-key"})
@AutoConfigureMockMvc
class McpServerDownTest {

    @Autowired
    MockMvc mvc;

    @Test
    void chatAnswers503() throws Exception {
        mvc.perform(post("/api/v1/chat").contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"hi\"}"))
                .andExpect(status().isServiceUnavailable());
    }
}
