package io.github.mrmtd.orchestrator.mcp;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Retry, error classification and result mapping, with the MCP client mocked. */
class McpToolClientTest {

    record Echo(String value) {
    }

    McpClientManager manager = mock(McpClientManager.class);
    McpSyncClient first = mock(McpSyncClient.class);
    McpSyncClient second = mock(McpSyncClient.class);
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    McpToolClient client = new McpToolClient(manager, JsonMapper.builder().build(), meters);

    @BeforeEach
    void setUp() {
        when(manager.client()).thenReturn(first, second);
    }

    @Test
    void mapsStructuredContentAndLeavesNullArgumentsOut() {
        when(first.callTool(any())).thenReturn(McpSchema.CallToolResult.builder()
                .structuredContent(Map.of("value", "hi")).build());
        Map<String, Object> args = new HashMap<>();
        args.put("a", 1);
        args.put("b", null);

        assertThat(client.call("echo", args, Echo.class)).isEqualTo(new Echo("hi"));
        verify(first).callTool(argThat(r -> r.arguments().equals(Map.of("a", 1))));
        assertThat(meters.get(McpToolClient.CALLS).tags("tool", "echo", "outcome", "success").timer().count())
                .isEqualTo(1);
    }

    @Test
    void fallsBackToJsonTextContent() {
        when(first.callTool(any())).thenReturn(McpSchema.CallToolResult.builder()
                .addTextContent("{\"value\":\"from text\"}").build());

        assertThat(client.call("echo", Map.of(), Echo.class).value()).isEqualTo("from text");
    }

    @Test
    void retriesOnceOnAFreshSessionAfterATransportFailure() {
        var failure = new RuntimeException("Session not found");
        when(first.callTool(any())).thenThrow(failure);
        when(second.callTool(any())).thenReturn(McpSchema.CallToolResult.builder()
                .structuredContent(Map.of("value", "ok")).build());

        assertThat(client.call("echo", Map.of(), Echo.class).value()).isEqualTo("ok");
        verify(manager).invalidate(first, failure);
        assertThat(meters.get(McpToolClient.RETRIES).tags("tool", "echo").counter().count()).isEqualTo(1);
    }

    @Test
    void givesUpAfterTheRetry() {
        when(first.callTool(any())).thenThrow(new RuntimeException("down"));
        when(second.callTool(any())).thenThrow(new RuntimeException("still down"));

        assertThatThrownBy(() -> client.call("echo", Map.of(), Echo.class))
                .isInstanceOf(McpUnavailableException.class);
        verify(manager, times(2)).invalidate(any(), any());
        assertThat(meters.get(McpToolClient.FAILURES).tags("tool", "echo", "outcome", "unavailable").counter().count())
                .isEqualTo(1);
    }

    @Test
    void jsonRpcInvalidParamsIsBadInputAndKeepsTheSession() {
        when(first.callTool(any())).thenThrow(new McpError(
                new McpSchema.JSONRPCResponse.JSONRPCError(McpSchema.ErrorCodes.INVALID_PARAMS, "limit: must be a number")));

        assertThatThrownBy(() -> client.call("echo", Map.of(), Echo.class))
                .isInstanceOfSatisfying(McpToolException.class, e -> {
                    assertThat(e.kind()).isEqualTo(McpToolException.Kind.INVALID_INPUT);
                    assertThat(e.getMessage()).isEqualTo("limit: must be a number");
                });
        verify(manager, never()).invalidate(any(), any());
    }

    @Test
    void toolErrorResultIsClassified() {
        when(first.callTool(any())).thenReturn(McpSchema.CallToolResult.builder()
                .isError(true).addTextContent("No load found with load number LD-000001").build());

        assertThatThrownBy(() -> client.call("getLoadStatus", Map.of(), Echo.class))
                .isInstanceOfSatisfying(McpToolException.class,
                        e -> assertThat(e.kind()).isEqualTo(McpToolException.Kind.NOT_FOUND));
        assertThat(meters.get(McpToolClient.FAILURES).tags("tool", "getLoadStatus", "outcome", "not_found")
                .counter().count()).isEqualTo(1);
        verify(manager, never()).invalidate(any(), any());
    }

    @Test
    void pingFailureDropsTheSession() {
        var failure = new RuntimeException("timeout");
        when(first.ping()).thenThrow(failure);

        assertThatThrownBy(client::ping).isInstanceOf(McpUnavailableException.class);
        verify(manager).invalidate(eq(first), eq(failure));
    }

    @Test
    void listsTools() {
        when(first.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(McpSchema.Tool.builder()
                .name("getLoadStatus").title("Get load status").description("d")
                .inputSchema(Map.of("type", "object"))
                .annotations(new McpSchema.ToolAnnotations(null, true, false, true, false, null))
                .build()), null));

        assertThat(client.listTools()).singleElement().satisfies(t -> {
            assertThat(t.name()).isEqualTo("getLoadStatus");
            assertThat(t.readOnly()).isTrue();
            assertThat(t.inputSchema()).containsEntry("type", "object");
        });
    }
}
