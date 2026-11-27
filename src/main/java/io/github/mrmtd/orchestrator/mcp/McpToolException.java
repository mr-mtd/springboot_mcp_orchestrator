package io.github.mrmtd.orchestrator.mcp;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A tool ran and returned {@code isError: true}. The transport MCP server puts a readable message in
 * the result; {@link #classify} turns it into a {@link Kind} the REST layer maps to a status code.
 */
public class McpToolException extends RuntimeException {

    public enum Kind {
        /** Unknown load or carrier. 404. */
        NOT_FOUND("not_found"),
        /** The server's per-session rate limit. 429. */
        RATE_LIMITED("rate_limited"),
        /** Bad input, or any other tool error. 400. */
        INVALID_INPUT("invalid_input");

        private final String outcome;

        Kind(String outcome) {
            this.outcome = outcome;
        }

        /** Value of the {@code outcome} metric tag. */
        public String outcome() {
            return outcome;
        }
    }

    private static final Pattern RETRY_IN = Pattern.compile("retry in (\\d+)s");
    private static final Pattern NOT_FOUND = Pattern.compile("\\bno \\w+ found\\b");

    private final String tool;
    private final Kind kind;
    private final long retryAfterSeconds;

    public McpToolException(String tool, Kind kind, String message, long retryAfterSeconds) {
        super(message);
        this.tool = tool;
        this.kind = kind;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public static McpToolException classify(String tool, String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("rate limit")) {
            Matcher m = RETRY_IN.matcher(lower);
            return new McpToolException(tool, Kind.RATE_LIMITED, message, m.find() ? Long.parseLong(m.group(1)) : 1);
        }
        if (NOT_FOUND.matcher(lower).find()) {
            return new McpToolException(tool, Kind.NOT_FOUND, message, 0);
        }
        return new McpToolException(tool, Kind.INVALID_INPUT, message, 0);
    }

    public String tool() {
        return tool;
    }

    public Kind kind() {
        return kind;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
