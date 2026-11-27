package io.github.mrmtd.orchestrator;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The whole flow with the real Spring AI OpenAI client and MCP client: a scripted fake of the OpenAI
 * API plays the model, and a real MCP server (MCP Java SDK) plays the transport MCP server.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ChatIntegrationTest {

    static final FakeTransportMcpServer mcp = new FakeTransportMcpServer();
    static final FakeOpenAi openAi = new FakeOpenAi();

    @DynamicPropertySource
    static void endpoints(DynamicPropertyRegistry registry) {
        registry.add("spring.ai.mcp.client.streamable-http.connections.transport.url", mcp::baseUrl);
        registry.add("spring.ai.openai.base-url", openAi::baseUrl);
        registry.add("spring.ai.openai.api-key", () -> "test-key");
    }

    @AfterAll
    static void stop() {
        mcp.close();
        openAi.close();
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    MeterRegistry meters;

    @BeforeEach
    void reset() {
        mcp.resetCalls();
        openAi.reset();
    }

    @Test
    void modelCallsAnMcpToolAndAnswers() throws Exception {
        openAi.thenToolCall("getLoadStatus", "{\"loadNumber\":\"420\"}")
                .thenAnswer("LD-000420 is in transit with Swift Freight, 3.4 hours late.");

        mvc.perform(post("/api/v1/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Where is load 420?\"}"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.answer").value("LD-000420 is in transit with Swift Freight, 3.4 hours late."))
                .andExpect(jsonPath("$.conversationId").isNotEmpty())
                .andExpect(jsonPath("$.toolCalls", hasSize(1)))
                .andExpect(jsonPath("$.toolCalls[0].tool").value("getLoadStatus"))
                .andExpect(jsonPath("$.toolCalls[0].arguments").value("{\"loadNumber\":\"420\"}"))
                .andExpect(jsonPath("$.toolCalls[0].outcome").value("success"))
                // usage is summed over both model calls
                .andExpect(jsonPath("$.usage.promptTokens").value(250))
                .andExpect(jsonPath("$.usage.completionTokens").value(50));

        assertThat(mcp.calls("getLoadStatus")).isEqualTo(1);
        var requests = openAi.requests();
        assertThat(requests).hasSize(2);
        // the model was offered the MCP server's tools, with the system prompt
        assertThat(requests.getFirst()).contains("getLoadStatus", "getCarrierDetails", "findDelayedShipments",
                "freight operations dashboard");
        // and got the tool result back
        assertThat(requests.get(1)).contains("Swift Freight", "call_0");

        assertThat(meters.get("orchestrator.tool.calls").tags("tool", "getLoadStatus", "outcome", "success")
                .timer().count()).isPositive();
        assertThat(meters.get("orchestrator.chat").tags("outcome", "success").timer().count()).isPositive();
        assertThat(meters.get("orchestrator.chat.tokens").tags("type", "prompt").summary().totalAmount())
                .isGreaterThanOrEqualTo(250);
        assertThat(meters.find("gen_ai.client.operation").timers()).isNotEmpty();
    }

    @Test
    void toolErrorsGoBackToTheModel() throws Exception {
        openAi.thenToolCall("getLoadStatus", "{\"loadNumber\":\"LD-999999\"}")
                .thenAnswer("I couldn't find load LD-999999.");

        mvc.perform(post("/api/v1/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Where is LD-999999?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("I couldn't find load LD-999999."))
                .andExpect(jsonPath("$.toolCalls[0].outcome").value("error"));

        assertThat(openAi.requests().get(1)).contains("No load found with load number LD-999999");
    }

    @Test
    void conversationIdKeepsHistory() throws Exception {
        openAi.thenAnswer("Hi!").thenAnswer("You said hello.");

        mvc.perform(post("/api/v1/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hello there\",\"conversationId\":\"c-1\"}"))
                .andExpect(jsonPath("$.conversationId").value("c-1"));
        mvc.perform(post("/api/v1/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"what did I say?\",\"conversationId\":\"c-1\"}"))
                .andExpect(status().isOk());

        assertThat(openAi.requests().get(1)).contains("hello there", "Hi!");
    }

    @Test
    void blankMessageIs400() throws Exception {
        mvc.perform(post("/api/v1/chat").contentType(MediaType.APPLICATION_JSON).content("{\"message\":\" \"}"))
                .andExpect(status().isBadRequest());
        assertThat(openAi.requests()).isEmpty();
    }

    @Test
    void modelFailureIs502() throws Exception {
        // nothing scripted: the fake answers 500 (Spring AI may retry, then gives up)
        mvc.perform(post("/api/v1/chat").contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"hi\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail").value("The assistant could not answer right now"));
        assertThat(meters.get("orchestrator.chat").tags("outcome", "error").timer().count()).isPositive();
    }

    @Test
    void corsAllowsTheReactDevServerOnly() throws Exception {
        mvc.perform(options("/api/v1/chat")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"));
        mvc.perform(options("/api/v1/chat")
                        .header(HttpHeaders.ORIGIN, "https://evil.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden());
    }

    @Test
    void exposesPrometheusMetrics() throws Exception {
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk());
    }
}
