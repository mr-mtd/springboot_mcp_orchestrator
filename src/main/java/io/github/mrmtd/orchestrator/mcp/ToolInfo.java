package io.github.mrmtd.orchestrator.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.Nullable;

import java.util.Map;

/** One tool the MCP server lists, as shown to the front end. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolInfo(String name, @Nullable String title, @Nullable String description,
                       @Nullable Boolean readOnly, Map<String, Object> inputSchema) {
}
