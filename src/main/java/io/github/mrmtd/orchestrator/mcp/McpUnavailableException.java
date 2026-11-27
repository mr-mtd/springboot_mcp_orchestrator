package io.github.mrmtd.orchestrator.mcp;

/** The MCP server couldn't be reached, or a call failed at the transport level. Maps to 503. */
public class McpUnavailableException extends RuntimeException {

    public McpUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
