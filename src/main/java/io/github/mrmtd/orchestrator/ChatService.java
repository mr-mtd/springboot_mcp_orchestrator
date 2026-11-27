package io.github.mrmtd.orchestrator;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.advisor.SessionAdvisors;
import org.springframework.ai.session.compaction.TurnCountTrigger;
import org.springframework.ai.session.compaction.WindowCompactionStrategy;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
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

    /** One tool call the model made while answering. */
    public record ToolCall(String tool, String arguments, String outcome, long durationMs) {
    }

    public record TokenUsage(int promptTokens, int completionTokens, int totalTokens) {
    }

    public record ChatReply(String conversationId, String answer, @Nullable String model,
                            List<ToolCall> toolCalls, TokenUsage usage, long durationMs) {
    }

    static final String SYSTEM_PROMPT = """
            You are the assistant for a freight operations dashboard. Answer questions about loads, \
            carriers and delayed shipments using the tools; never make up loads, carriers or numbers. \
            Load numbers look like LD-000420. If a tool returns an error, explain it plainly or try \
            again with corrected input. Keep answers short and use bullet points or a small table \
            for lists.""";

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final ChatClient chatClient;
    private final ToolCallbackProvider mcpTools;
    private final MeterRegistry meters;

    public ChatService(ChatClient.Builder builder, ToolCallbackProvider mcpTools, MeterRegistry meters) {
        // In-memory conversation history: kept for an hour, trimmed to the last 10 turns after 20.
        var sessions = DefaultSessionService.builder()
                .sessionRepository(InMemorySessionRepository.builder().build())
                .defaultTimeToLive(Duration.ofHours(1))
                .build();
        this.chatClient = builder
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(SessionAdvisors.builder(sessions)
                        .compaction(new TurnCountTrigger(20), WindowCompactionStrategy.builder().lastTurns(10).build())
                        .build())
                .build();
        this.mcpTools = mcpTools;
        this.meters = meters;
    }

    public ChatReply chat(String message, @Nullable String conversationId) {
        String id = conversationId == null || conversationId.isBlank() ? UUID.randomUUID().toString() : conversationId;
        long start = System.nanoTime();
        String outcome = "error";
        try {
            List<ToolCall> calls = Collections.synchronizedList(new ArrayList<>());
            ChatResponse response = chatClient.prompt()
                    .user(message)
                    .tools((Object[]) tools(calls))
                    .advisors(SessionAdvisors.session(id))
                    .call()
                    .chatResponse();
            if (response == null || response.getResult() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "The model returned no answer");
            }
            Usage usage = response.getMetadata().getUsage();
            var tokens = new TokenUsage(n(usage.getPromptTokens()), n(usage.getCompletionTokens()), n(usage.getTotalTokens()));
            recordTokens(tokens);
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            outcome = "success";
            log.info("chat conversation={} outcome=success toolCalls={} promptTokens={} completionTokens={} durationMs={}",
                    id, calls.size(), tokens.promptTokens(), tokens.completionTokens(), ms);
            return new ChatReply(id, response.getResult().getOutput().getText(), response.getMetadata().getModel(),
                    List.copyOf(calls), tokens, ms);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("chat conversation={} outcome=error durationMs={}", id,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "The assistant could not answer right now", e);
        } finally {
            Timer.builder("orchestrator.chat").description("Chat requests, start to answer")
                    .tag("outcome", outcome).publishPercentileHistogram().register(meters)
                    .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    /** The MCP server's tools (listed once, then cached by Spring AI), each wrapped to time and record calls. */
    private ToolCallback[] tools(List<ToolCall> calls) {
        ToolCallback[] discovered;
        try {
            discovered = mcpTools.getToolCallbacks();
        } catch (RuntimeException e) {
            log.warn("MCP server unavailable, can't list tools cause=\"{}\"", e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "The transport data service is unavailable. Try again shortly.", e);
        }
        return Arrays.stream(discovered)
                .map(tool -> new MeteredToolCallback(tool, calls, meters))
                .toArray(ToolCallback[]::new);
    }

    private void recordTokens(TokenUsage tokens) {
        record("prompt", tokens.promptTokens());
        record("completion", tokens.completionTokens());
    }

    private void record(String type, int count) {
        meters.summary("orchestrator.chat.tokens", "type", type).record(count);
    }

    private static int n(@Nullable Integer value) {
        return value == null ? 0 : value;
    }
}
