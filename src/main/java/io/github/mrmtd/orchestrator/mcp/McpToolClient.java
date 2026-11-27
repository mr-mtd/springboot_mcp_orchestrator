package io.github.mrmtd.orchestrator.mcp;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Calls tools on the transport MCP server. Every call:
 *
 * <ol>
 *   <li>puts {@code mcpTool} into the logging MDC</li>
 *   <li>sends {@code tools/call}, retrying once on a fresh session if the transport fails (the tools
 *       are read-only and idempotent, so a retry is safe)</li>
 *   <li>turns an {@code isError} result into {@link McpToolException}, and a transport failure into
 *       {@link McpUnavailableException}</li>
 *   <li>maps {@code structuredContent} onto the requested record</li>
 *   <li>records metrics and writes one INFO line with the outcome and duration</li>
 * </ol>
 *
 * <p>Metrics:
 * <ul>
 *   <li>{@code orchestrator.mcp.tool.calls}: timer with percentile histogram, tags {@code tool},
 *       {@code outcome} ({@code success}, {@code not_found}, {@code invalid_input},
 *       {@code rate_limited}, {@code unavailable}, {@code error})</li>
 *   <li>{@code orchestrator.mcp.tool.failures}: counter, same tags, every outcome but success</li>
 *   <li>{@code orchestrator.mcp.tool.retries}: counter, tag {@code tool}</li>
 * </ul>
 */
@Component
public class McpToolClient {

    private static final Logger log = LoggerFactory.getLogger(McpToolClient.class);

    static final String CALLS = "orchestrator.mcp.tool.calls";
    static final String FAILURES = "orchestrator.mcp.tool.failures";
    static final String RETRIES = "orchestrator.mcp.tool.retries";

    private final McpClientManager clients;
    private final JsonMapper json;
    private final MeterRegistry meters;

    public McpToolClient(McpClientManager clients, JsonMapper json, MeterRegistry meters) {
        this.clients = clients;
        this.json = json;
        this.meters = meters;
    }

    /** Calls {@code tool} and maps its structured result onto {@code type}. Null arguments are left out. */
    public <T> T call(String tool, Map<String, ?> arguments, Class<T> type) {
        Map<String, Object> args = new LinkedHashMap<>();
        arguments.forEach((k, v) -> {
            if (v != null) args.put(k, v);
        });

        try (var ignored = MDC.putCloseable("mcpTool", tool)) {
            long start = System.nanoTime();
            try {
                McpSchema.CallToolResult result = withRetry(tool,
                        c -> c.callTool(new McpSchema.CallToolRequest(tool, args)));
                if (Boolean.TRUE.equals(result.isError())) {
                    throw McpToolException.classify(tool, text(result));
                }
                T value = json.convertValue(body(result), type);
                finish(tool, "success", start, null);
                return value;
            } catch (McpToolException e) {
                finish(tool, e.kind().outcome(), start, e);
                throw e;
            } catch (McpUnavailableException e) {
                finish(tool, "unavailable", start, e);
                throw e;
            } catch (RuntimeException e) {
                finish(tool, "error", start, e);
                throw e;
            }
        }
    }

    /** The tools the MCP server lists ({@code tools/list}). */
    public List<ToolInfo> listTools() {
        long start = System.nanoTime();
        try (var ignored = MDC.putCloseable("mcpTool", "tools/list")) {
            List<ToolInfo> tools = withRetry("tools/list", McpSyncClient::listTools).tools().stream()
                    .map(t -> new ToolInfo(t.name(), t.title(), t.description(),
                            t.annotations() == null ? null : t.annotations().readOnlyHint(),
                            t.inputSchema() == null ? Map.of() : json.convertValue(t.inputSchema(), Map.class)))
                    .toList();
            log.info("mcpTool=tools/list outcome=success count={} durationMs={}", tools.size(), millisSince(start));
            return tools;
        }
    }

    /** Sends an MCP ping. Used by the health check; doesn't retry. */
    public long ping() {
        long start = System.nanoTime();
        McpSyncClient client = clients.client();
        try {
            client.ping();
            return millisSince(start);
        } catch (RuntimeException e) {
            clients.invalidate(client, e);
            throw new McpUnavailableException("MCP ping failed", e);
        }
    }

    private <R> R withRetry(String tool, Function<McpSyncClient, R> call) {
        McpSyncClient client = clients.client();
        try {
            return call.apply(client);
        } catch (RuntimeException first) {
            McpError rejected = requestRejected(first);
            if (rejected != null) {
                // The server answered with a JSON-RPC error about the request itself; the session is fine.
                throw new McpToolException(tool, McpToolException.Kind.INVALID_INPUT,
                        rejected.getJsonRpcError().message(), 0);
            }
            clients.invalidate(client, first);
            Counter.builder(RETRIES).description("MCP calls retried on a fresh session after a transport failure")
                    .tag("tool", tool).register(meters).increment();
            log.warn("mcpTool={} transport failure, retrying once on a new session cause=\"{}\"",
                    tool, McpClientManager.rootMessage(first));
            McpSyncClient retryClient = clients.client();
            try {
                return call.apply(retryClient);
            } catch (RuntimeException second) {
                clients.invalidate(retryClient, second);
                throw new McpUnavailableException("MCP call " + tool + " failed", second);
            }
        }
    }

    /** A JSON-RPC error saying the request was bad (invalid params, unknown method), if that's what this is. */
    static McpError requestRejected(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (c instanceof McpError e && e.getJsonRpcError() != null) {
                Integer code = e.getJsonRpcError().code();
                if (code != null && (code == McpSchema.ErrorCodes.INVALID_PARAMS || code == McpSchema.ErrorCodes.METHOD_NOT_FOUND
                        || code == McpSchema.ErrorCodes.INVALID_REQUEST)) {
                    return e;
                }
            }
        }
        return null;
    }

    private Object body(McpSchema.CallToolResult result) {
        if (result.structuredContent() != null) {
            return result.structuredContent();
        }
        // Older servers only send the JSON as text content.
        return json.readValue(text(result), Map.class);
    }

    private static String text(McpSchema.CallToolResult result) {
        if (result.content() == null) {
            return "";
        }
        return result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(c -> ((McpSchema.TextContent) c).text())
                .collect(Collectors.joining("\n"));
    }

    private void finish(String tool, String outcome, long start, Exception e) {
        long nanos = System.nanoTime() - start;
        Timer.builder(CALLS)
                .description("MCP tool calls made by the orchestrator, by tool and outcome")
                .tags("tool", tool, "outcome", outcome)
                .publishPercentileHistogram()
                .register(meters)
                .record(nanos, TimeUnit.NANOSECONDS);
        long ms = TimeUnit.NANOSECONDS.toMillis(nanos);
        if ("success".equals(outcome)) {
            log.info("mcpTool={} outcome=success durationMs={}", tool, ms);
            return;
        }
        Counter.builder(FAILURES)
                .description("MCP tool calls that did not succeed, by tool and outcome")
                .tags("tool", tool, "outcome", outcome)
                .register(meters)
                .increment();
        switch (outcome) {
            case "not_found", "invalid_input" ->
                    log.info("mcpTool={} outcome={} durationMs={} message=\"{}\"", tool, outcome, ms, e.getMessage());
            case "rate_limited", "unavailable" ->
                    log.warn("mcpTool={} outcome={} durationMs={} message=\"{}\"", tool, outcome, ms, e.getMessage());
            default -> log.error("mcpTool={} outcome={} durationMs={}", tool, outcome, ms, e);
        }
    }

    private static long millisSince(long start) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }
}
