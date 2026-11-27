package io.github.mrmtd.orchestrator.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.session.DefaultSessionService;
import org.springframework.ai.session.InMemorySessionRepository;
import org.springframework.ai.session.advisor.SessionAdvisors;
import org.springframework.ai.session.compaction.TurnCountTrigger;
import org.springframework.ai.session.compaction.WindowCompactionStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/** The OpenAI chat client: system prompt and conversation history. */
@Configuration
public class ChatClientConfig {

    public static final String SYSTEM_PROMPT = """
            You are the assistant for a freight operations dashboard. Answer questions about loads, \
            carriers and delayed shipments using the tools; never make up loads, carriers or numbers. \
            Load numbers look like LD-000420. If a tool returns an error, explain it plainly or try \
            again with corrected input. Keep answers short and use bullet points or a small table \
            for lists.""";

    @Bean
    ChatClient chatClient(ChatClient.Builder builder) {
        // In-memory history per conversation: kept for an hour, trimmed to the last 10 turns after 20.
        var sessions = DefaultSessionService.builder()
                .sessionRepository(InMemorySessionRepository.builder().build())
                .defaultTimeToLive(Duration.ofHours(1))
                .build();
        return builder
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(SessionAdvisors.builder(sessions)
                        .compaction(new TurnCountTrigger(20), WindowCompactionStrategy.builder().lastTurns(10).build())
                        .build())
                .build();
    }
}
