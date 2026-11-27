package io.github.mrmtd.orchestrator.mcp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The transport MCP server's error messages map to the right kind. */
class McpToolExceptionTest {

    @Test
    void notFound() {
        assertThat(McpToolException.classify("getLoadStatus", "No load found with load number LD-999999").kind())
                .isEqualTo(McpToolException.Kind.NOT_FOUND);
        assertThat(McpToolException.classify("getCarrierDetails", "No carrier found with MC number MC1").kind())
                .isEqualTo(McpToolException.Kind.NOT_FOUND);
    }

    @Test
    void rateLimitedWithRetryAfter() {
        var e = McpToolException.classify("getLoadStatus", "Rate limit exceeded, retry in 7s");
        assertThat(e.kind()).isEqualTo(McpToolException.Kind.RATE_LIMITED);
        assertThat(e.retryAfterSeconds()).isEqualTo(7);
    }

    @Test
    void rateLimitedWithoutAHintDefaultsToOneSecond() {
        assertThat(McpToolException.classify("t", "rate limit exceeded").retryAfterSeconds()).isEqualTo(1);
    }

    @Test
    void anythingElseIsInvalidInput() {
        assertThat(McpToolException.classify("t", "limit must be between 1 and 100").kind())
                .isEqualTo(McpToolException.Kind.INVALID_INPUT);
        assertThat(McpToolException.classify("t", "Give exactly one of carrierId or mcNumber").kind())
                .isEqualTo(McpToolException.Kind.INVALID_INPUT);
    }
}
