package io.github.mrmtd.orchestrator.mcp;

import io.github.mrmtd.orchestrator.model.ToolCall;

import io.micrometer.core.instrument.MeterRegistry;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Wraps one MCP tool so each call the model makes is timed ({@code orchestrator.tool.calls}, tags
 * {@code tool} and {@code outcome}), logged, and added to the chat reply's {@code toolCalls}.
 *
 * <p>A tool error (unknown load, bad input, rate limit) is thrown by Spring AI's MCP callback as a
 * ToolExecutionException; it is recorded as {@code outcome=error} and rethrown, and Spring AI hands
 * the message to the model so it can explain or retry.
 */
public class MeteredToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(MeteredToolCallback.class);

    private final ToolCallback delegate;
    private final List<ToolCall> calls;
    private final MeterRegistry meters;

    public MeteredToolCallback(ToolCallback delegate, List<ToolCall> calls, MeterRegistry meters) {
        this.delegate = delegate;
        this.calls = calls;
        this.meters = meters;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, @Nullable ToolContext toolContext) {
        String tool = getToolDefinition().name();
        long start = System.nanoTime();
        String outcome = "error";
        try {
            String result = delegate.call(toolInput, toolContext);
            outcome = "success";
            return result;
        } finally {
            long nanos = System.nanoTime() - start;
            long ms = TimeUnit.NANOSECONDS.toMillis(nanos);
            meters.timer("orchestrator.tool.calls", "tool", tool, "outcome", outcome).record(nanos, TimeUnit.NANOSECONDS);
            calls.add(new ToolCall(tool, toolInput, outcome, ms));
            if ("success".equals(outcome)) {
                log.info("tool={} outcome=success durationMs={} arguments={}", tool, ms, toolInput);
            } else {
                log.warn("tool={} outcome=error durationMs={} arguments={}", tool, ms, toolInput);
            }
        }
    }
}
