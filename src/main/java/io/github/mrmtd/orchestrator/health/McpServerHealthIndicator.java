package io.github.mrmtd.orchestrator.health;

import io.github.mrmtd.orchestrator.mcp.McpClientFactory;
import io.github.mrmtd.orchestrator.mcp.McpToolClient;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.stereotype.Component;

/** {@code /actuator/health} component {@code mcpServer}: UP when an MCP ping to the server succeeds. */
@Component("mcpServer")
public class McpServerHealthIndicator extends AbstractHealthIndicator {

    private final McpToolClient tools;
    private final McpClientFactory factory;

    public McpServerHealthIndicator(McpToolClient tools, McpClientFactory factory) {
        super("MCP server health check failed");
        this.tools = tools;
        this.factory = factory;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        builder.withDetail("target", factory.target());
        long pingMs = tools.ping();
        builder.up().withDetail("pingMs", pingMs);
    }
}
