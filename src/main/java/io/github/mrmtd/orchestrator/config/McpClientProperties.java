package io.github.mrmtd.orchestrator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * How to reach the transport MCP server.
 *
 * @param baseUrl               scheme, host and port of the MCP server, e.g. {@code http://localhost:8080}
 * @param endpoint              path of the Streamable HTTP endpoint on that server
 * @param connectTimeout        TCP connect timeout
 * @param requestTimeout        how long to wait for any one MCP request (initialize, tools/call, ping)
 * @param initializationTimeout how long to wait for the initialize handshake
 * @param clientName            name sent in clientInfo on initialize; the server logs it per session
 * @param clientVersion         version sent in clientInfo
 */
@ConfigurationProperties("orchestrator.mcp")
public record McpClientProperties(
        @DefaultValue("http://localhost:8080") String baseUrl,
        @DefaultValue("/mcp") String endpoint,
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("10s") Duration requestTimeout,
        @DefaultValue("10s") Duration initializationTimeout,
        @DefaultValue("transport-mcp-orchestrator") String clientName,
        @DefaultValue("0.1.0") String clientVersion) {
}
