package io.github.mrmtd.orchestrator.exception;

/** The MCP server couldn't be reached to list its tools. Answered with 503. */
public class McpUnavailableException extends RuntimeException {

    public McpUnavailableException(Throwable cause) {
        super("The transport data service is unavailable. Try again shortly.", cause);
    }
}
