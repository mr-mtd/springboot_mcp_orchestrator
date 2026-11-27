package io.github.mrmtd.orchestrator.mcp;

import io.github.mrmtd.orchestrator.config.McpClientProperties;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;

/** Builds a new, not yet initialized, MCP client for the transport MCP server. */
@Component
public class McpClientFactory {

    private final McpClientProperties properties;

    public McpClientFactory(McpClientProperties properties) {
        this.properties = properties;
    }

    public McpSyncClient create() {
        var transport = HttpClientStreamableHttpTransport.builder(properties.baseUrl())
                .endpoint(properties.endpoint())
                .connectTimeout(properties.connectTimeout())
                .build();
        return McpClient.sync(transport)
                .clientInfo(new McpSchema.Implementation(properties.clientName(), properties.clientVersion()))
                .requestTimeout(properties.requestTimeout())
                .initializationTimeout(properties.initializationTimeout())
                .build();
    }

    public String target() {
        return properties.baseUrl() + properties.endpoint();
    }
}
