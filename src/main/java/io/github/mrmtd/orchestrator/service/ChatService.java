package io.github.mrmtd.orchestrator.service;

import io.github.mrmtd.orchestrator.exception.LlmException;
import io.github.mrmtd.orchestrator.exception.McpUnavailableException;
import io.github.mrmtd.orchestrator.mcp.MeteredToolCallback;
import io.github.mrmtd.orchestrator.model.ChatReply;
import io.github.mrmtd.orchestrator.model.TokenUsage;
import io.github.mrmtd.orchestrator.model.ToolCall;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.session.advisor.SessionAdvisors;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Answers one chat message: sends it to the model with the MCP server's tools, lets the model call
 * them (Spring AI runs the tool loop), and returns the answer with the tool calls and token usage.
 *
 * <p>Metrics, besides Spring AI's own {@code gen_ai.client.operation} (LLM latency) and
 * {@code gen_ai.client.token.usage} (tokens by type):
 * <ul>
 *   <li>{@code orchestrator.chat}: timer of whole chat requests, tag {@code outcome}</li>
 *   <li>{@code orchestrator.chat.tokens}: distribution summary of tokens per chat, tag {@code type}</li>
 *   <li>{@code orchestrator.tool.calls}: timer per MCP tool call, see {@link MeteredToolCallback}</li>
 * </ul>
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final ChatClient chatClient;
    private final ToolCallbackProvider mcpTools;
    private final MeterRegistry meters;

    public ChatService(ChatClient chatClient, ToolCallbackProvider mcpTools, MeterRegistry meters) {
        this.chatClient = chatClient;
        this.mcpTools = mcpTools;
        this.meters = meters;
    }

    public ChatReply chat(String message, @Nullable String conversationId) {
        String id = conversationId == null || conversationId.isBlank() ? UUID.randomUUID().toString() : conversationId;
        long start = System.nanoTime();
        String outcome = "error";
        try {
            List<ToolCall> calls = Collections.synchronizedList(new ArrayList<>());
            ChatResponse response = callModel(message, id, tools(calls));
            Usage usage = response.getMetadata().getUsage();
            var tokens = new TokenUsage(n(usage.getPromptTokens()), n(usage.getCompletionTokens()), n(usage.getTotalTokens()));
            meters.summary("orchestrator.chat.tokens", "type", "prompt").record(tokens.promptTokens());
            meters.summary("orchestrator.chat.tokens", "type", "completion").record(tokens.completionTokens());
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            outcome = "success";
            log.info("chat conversation={} outcome=success toolCalls={} promptTokens={} completionTokens={} durationMs={}",
                    id, calls.size(), tokens.promptTokens(), tokens.completionTokens(), ms);
            return new ChatReply(id, response.getResult().getOutput().getText(), response.getMetadata().getModel(),
                    List.copyOf(calls), tokens, ms);
        } finally {
            Timer.builder("orchestrator.chat").description("Chat requests, start to answer")
                    .tag("outcome", outcome).publishPercentileHistogram().register(meters)
                    .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    private ChatResponse callModel(String message, String conversationId, ToolCallback[] tools) {
        try {
            ChatResponse response = chatClient.prompt()
                    .user(message)
                    .tools((Object[]) tools)
                    .advisors(SessionAdvisors.session(conversationId))
                    .call()
                    .chatResponse();
            if (response == null || response.getResult() == null) {
                throw new LlmException();
            }
            return response;
        } catch (LlmException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("chat conversation={} outcome=error model call failed", conversationId, e);
            throw new LlmException(e);
        }
    }

    /** The MCP server's tools (listed once, then cached by Spring AI), each wrapped to time and record calls. */
    private ToolCallback[] tools(List<ToolCall> calls) {
        ToolCallback[] discovered;
        try {
            discovered = mcpTools.getToolCallbacks();
        } catch (RuntimeException e) {
            log.warn("MCP server unavailable, can't list tools cause=\"{}\"", e.getMessage());
            throw new McpUnavailableException(e);
        }
        return Arrays.stream(discovered)
                .map(tool -> new MeteredToolCallback(tool, calls, meters))
                .toArray(ToolCallback[]::new);
    }

    private static int n(@Nullable Integer value) {
        return value == null ? 0 : value;
    }
}
