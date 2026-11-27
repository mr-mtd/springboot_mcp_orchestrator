package io.github.mrmtd.orchestrator.support;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.startup.Tomcat;

import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A stand-in for the transport MCP server: the real MCP Java SDK server over Streamable HTTP on an
 * embedded Tomcat, with the same three tools and the same error messages, over a few fixed rows.
 *
 * <ul>
 *   <li>LD-000420 (or 420): in transit, carrier 7. LD-000500: no carrier. Anything else: not found.</li>
 *   <li>Carriers 7 and 9 exist; any other id or MC number: not found.</li>
 *   <li>Delayed shipments: four loads, carriers 7, 7, 9 and 11 (11 has no profile).</li>
 *   <li>Load number RATE: rate limit error.</li>
 * </ul>
 */
public final class FakeTransportMcpServer implements AutoCloseable {

    private final Tomcat tomcat = new Tomcat();
    private final McpSyncServer server;
    private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();

    public FakeTransportMcpServer() {
        var transport = HttpServletStreamableServerTransportProvider.builder().mcpEndpoint("/mcp").build();
        server = McpServer.sync(transport)
                .serverInfo("fake-transport-mcp-server", "test")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .tools(tool("getLoadStatus", this::loadStatus),
                        tool("getCarrierDetails", this::carrierDetails),
                        tool("findDelayedShipments", this::delayedShipments))
                .build();
        try {
            tomcat.setBaseDir(Files.createTempDirectory("fake-mcp").toString());
            tomcat.setPort(0);
            Context context = tomcat.addContext("", null);
            Tomcat.addServlet(context, "mcp", transport).setAsyncSupported(true);
            context.addServletMappingDecoded("/*", "mcp");
            tomcat.getConnector();
            tomcat.start();
        } catch (Exception e) {
            throw new IllegalStateException("Could not start fake MCP server", e);
        }
    }

    public String baseUrl() {
        return "http://localhost:" + tomcat.getConnector().getLocalPort();
    }

    public int calls(String tool) {
        return calls.getOrDefault(tool, new AtomicInteger()).get();
    }

    public void resetCalls() {
        calls.clear();
    }

    @Override
    public void close() {
        server.close();
        try {
            tomcat.stop();
            tomcat.destroy();
        } catch (LifecycleException e) {
            throw new IllegalStateException(e);
        }
    }

    // --- tools ---

    private McpSchema.CallToolResult loadStatus(Map<String, Object> args) {
        String raw = String.valueOf(args.get("loadNumber"));
        if ("RATE".equals(raw)) {
            return error("Rate limit exceeded, retry in 3s");
        }
        if (!raw.matches("(LD-)?\\d+")) {
            return error("loadNumber should look like LD-000420, got: " + raw);
        }
        String normalized = "LD-%06d".formatted(Integer.parseInt(raw.replace("LD-", "")));
        return switch (normalized) {
            case "LD-000420" -> ok(load(normalized, 7L, "Swift Freight"));
            case "LD-000500" -> ok(load(normalized, null, null));
            default -> error("No load found with load number " + normalized);
        };
    }

    private McpSchema.CallToolResult carrierDetails(Map<String, Object> args) {
        Object id = args.get("carrierId");
        Object mc = args.get("mcNumber");
        if ((id == null) == (mc == null)) {
            return error("Give exactly one of carrierId or mcNumber");
        }
        if ("MC155433".equals(mc) || (id != null && ((Number) id).longValue() == 7)) {
            return ok(carrier(7, "Swift Freight", "SATISFACTORY", 3, 92.5));
        }
        if (id != null && ((Number) id).longValue() == 9) {
            return ok(carrier(9, "Lone Star Haulers", "CONDITIONAL", 5, 71.0));
        }
        return error(id != null ? "No carrier found with id " + id : "No carrier found with MC number " + mc);
    }

    private McpSchema.CallToolResult delayedShipments(Map<String, Object> args) {
        var shipments = List.of(
                shipment("LD-000101", 7, "Swift Freight", "TX", "GA", 9.5),
                shipment("LD-000102", 7, "Swift Freight", "TX", "FL", 4.5),
                shipment("LD-000103", 9, "Lone Star Haulers", "OK", "GA", 12.0),
                shipment("LD-000104", 11, "Ghost Lines", "CA", "NV", 3.0));
        Object limit = args.get("limit");
        var limited = limit == null ? shipments : shipments.subList(0, Math.min(shipments.size(), ((Number) limit).intValue()));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("minHoursLate", args.getOrDefault("minHoursLate", 2.0));
        body.put("includeDelivered", args.getOrDefault("includeDelivered", false));
        body.put("count", limited.size());
        body.put("shipments", limited);
        return ok(body);
    }

    // --- rows ---

    private static Map<String, Object> load(String loadNumber, Long carrierId, String carrierName) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("loadNumber", loadNumber);
        m.put("status", carrierId == null ? "TENDERED" : "IN_TRANSIT");
        if (carrierId != null) {
            m.put("carrierId", carrierId);
            m.put("carrierName", carrierName);
        }
        m.put("equipmentType", "DRY_VAN");
        m.put("originCity", "Dallas");
        m.put("originState", "TX");
        m.put("destinationCity", "Atlanta");
        m.put("destinationState", "GA");
        m.put("miles", 781);
        m.put("pickupScheduledAt", "2026-11-25T08:00:00Z");
        m.put("deliveryScheduledAt", "2026-11-26T18:00:00Z");
        m.put("hoursLate", 3.4);
        m.put("late", true);
        m.put("lastEvent", Map.of("eventType", "DELAY_REPORTED", "occurredAt", "2026-11-26T20:00:00Z",
                "city", "Birmingham", "state", "AL", "delayReason", "WEATHER"));
        return m;
    }

    private static Map<String, Object> carrier(long id, String name, String rating, int openLoads, double onTime) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("mcNumber", "MC1554" + id);
        m.put("dotNumber", "DOT" + id);
        m.put("safetyRating", rating);
        m.put("active", true);
        m.put("openLoads", openLoads);
        m.put("deliveredLast90Days", 40);
        m.put("lateDeliveriesLast90Days", 3);
        m.put("onTimePctLast90Days", onTime);
        return m;
    }

    private static Map<String, Object> shipment(String loadNumber, long carrierId, String carrierName,
                                                String origin, String destination, double hoursLate) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("loadNumber", loadNumber);
        m.put("status", "IN_TRANSIT");
        m.put("carrierId", carrierId);
        m.put("carrierName", carrierName);
        m.put("originCity", "City");
        m.put("originState", origin);
        m.put("destinationCity", "Town");
        m.put("destinationState", destination);
        m.put("deliveryScheduledAt", "2026-11-26T18:00:00Z");
        m.put("hoursLate", hoursLate);
        m.put("latestDelayReason", "TRAFFIC");
        return m;
    }

    // --- plumbing ---

    private interface Handler {
        McpSchema.CallToolResult handle(Map<String, Object> args);
    }

    private McpServerFeatures.SyncToolSpecification tool(String name, Handler handler) {
        var tool = McpSchema.Tool.builder()
                .name(name)
                .title(name)
                .description("Fake " + name)
                .inputSchema(Map.of("type", "object", "properties", Map.of()))
                .annotations(new McpSchema.ToolAnnotations(name, true, false, true, false, null))
                .build();
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    calls.computeIfAbsent(name, k -> new AtomicInteger()).incrementAndGet();
                    return handler.handle(request.arguments() == null ? Map.of() : request.arguments());
                })
                .build();
    }

    private static McpSchema.CallToolResult ok(Map<String, Object> body) {
        return McpSchema.CallToolResult.builder().structuredContent(body).addTextContent("ok").build();
    }

    private static McpSchema.CallToolResult error(String message) {
        return McpSchema.CallToolResult.builder().isError(true).addTextContent(message).build();
    }
}
