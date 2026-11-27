package io.github.mrmtd.orchestrator.mcp;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.modelcontextprotocol.client.McpSyncClient;
import jakarta.annotation.PreDestroy;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * Owns the one MCP session the orchestrator keeps with the transport MCP server.
 *
 * <p>The session is opened lazily on first use, so the orchestrator starts even when the MCP server
 * is down. When a call fails at the transport level (server restarted, session expired, network)
 * the caller {@link #invalidate invalidates} the client and the next call opens a fresh session.
 *
 * <p>Metrics:
 * <ul>
 *   <li>{@code orchestrator.mcp.connect}: timer of initialize handshakes, tag {@code outcome}</li>
 *   <li>{@code orchestrator.mcp.sessions.invalidated}: counter of sessions dropped after a failure</li>
 *   <li>{@code orchestrator.mcp.connected}: gauge, 1 while a session is open</li>
 * </ul>
 */
@Component
public class McpClientManager {

    private static final Logger log = LoggerFactory.getLogger(McpClientManager.class);

    private final McpClientFactory factory;
    private final MeterRegistry meters;
    private final Counter invalidated;

    private volatile @Nullable McpSyncClient client;
    private volatile @Nullable Instant connectedAt;

    public McpClientManager(McpClientFactory factory, MeterRegistry meters) {
        this.factory = factory;
        this.meters = meters;
        this.invalidated = Counter.builder("orchestrator.mcp.sessions.invalidated")
                .description("MCP sessions dropped after a transport failure")
                .register(meters);
        Gauge.builder("orchestrator.mcp.connected", this, m -> m.client != null ? 1 : 0)
                .description("1 while the orchestrator has an open MCP session")
                .register(meters);
    }

    /** The open client, connecting first if needed. Throws {@link McpUnavailableException} if it can't connect. */
    public McpSyncClient client() {
        McpSyncClient current = client;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (client == null) {
                client = connect();
                connectedAt = Instant.now();
            }
            return client;
        }
    }

    /** Drops the client if it is still {@code failed}; the next call to {@link #client()} reconnects. */
    public void invalidate(McpSyncClient failed, Throwable cause) {
        synchronized (this) {
            if (client != failed) {
                return; // another thread already replaced it
            }
            client = null;
            Duration lasted = connectedAt == null ? Duration.ZERO : Duration.between(connectedAt, Instant.now());
            connectedAt = null;
            invalidated.increment();
            log.warn("MCP session dropped after failure, will reconnect on next call target={} lasted={} cause=\"{}\"",
                    factory.target(), lasted, rootMessage(cause));
        }
        closeQuietly(failed);
    }

    public boolean isConnected() {
        return client != null;
    }

    private McpSyncClient connect() {
        long start = System.nanoTime();
        McpSyncClient fresh = factory.create();
        try {
            var init = fresh.initialize();
            long nanos = System.nanoTime() - start;
            recordConnect("success", nanos);
            log.info("MCP session opened target={} server={} serverVersion={} protocol={} durationMs={}",
                    factory.target(), init.serverInfo().name(), init.serverInfo().version(),
                    init.protocolVersion(), TimeUnit.NANOSECONDS.toMillis(nanos));
            return fresh;
        } catch (RuntimeException e) {
            long nanos = System.nanoTime() - start;
            recordConnect("failure", nanos);
            log.warn("MCP connect failed target={} durationMs={} cause=\"{}\"",
                    factory.target(), TimeUnit.NANOSECONDS.toMillis(nanos), rootMessage(e));
            closeQuietly(fresh);
            throw new McpUnavailableException("MCP server unavailable at " + factory.target(), e);
        }
    }

    private void recordConnect(String outcome, long nanos) {
        Timer.builder("orchestrator.mcp.connect")
                .description("MCP initialize handshakes")
                .tag("outcome", outcome)
                .register(meters)
                .record(nanos, TimeUnit.NANOSECONDS);
    }

    @PreDestroy
    void shutdown() {
        McpSyncClient current;
        synchronized (this) {
            current = client;
            client = null;
        }
        if (current != null) {
            log.info("Closing MCP session target={}", factory.target());
            current.closeGracefully();
        }
    }

    private static void closeQuietly(McpSyncClient c) {
        try {
            c.close();
        } catch (RuntimeException e) {
            log.debug("Ignoring error while closing MCP client", e);
        }
    }

    static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + (root.getMessage() == null ? "" : ": " + root.getMessage());
    }
}
