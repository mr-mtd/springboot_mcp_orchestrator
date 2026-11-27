package io.github.mrmtd.orchestrator.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The executor that fans out MCP calls in parallel (for example, carrier details for the
 * dashboard). Virtual threads, since each task mostly waits on HTTP. Each task gets the caller's
 * logging MDC, so its log lines keep the request id.
 */
@Configuration
public class ExecutorConfig {

    public static final String FAN_OUT_EXECUTOR = "mcpFanOutExecutor";

    @Bean(name = FAN_OUT_EXECUTOR, destroyMethod = "close")
    ExecutorService mcpFanOutExecutor(MeterRegistry meters) {
        ExecutorService virtualThreads = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("mcp-fanout-", 0).factory());
        return new MdcExecutorService(virtualThreads, meters);
    }

    /**
     * Copies the submitting thread's MDC into each task, and records
     * {@code orchestrator.fanout.tasks} (timer: task count and run time) and
     * {@code orchestrator.fanout.active} (gauge: tasks running now).
     */
    static final class MdcExecutorService extends java.util.concurrent.AbstractExecutorService {

        private final ExecutorService delegate;
        private final Timer tasks;
        private final AtomicInteger active = new AtomicInteger();

        MdcExecutorService(ExecutorService delegate, MeterRegistry meters) {
            this.delegate = delegate;
            this.tasks = Timer.builder("orchestrator.fanout.tasks")
                    .description("Parallel MCP calls run on the fan-out executor")
                    .register(meters);
            Gauge.builder("orchestrator.fanout.active", active, AtomicInteger::get)
                    .description("Fan-out tasks running now")
                    .register(meters);
        }

        @Override
        public void execute(Runnable task) {
            Map<String, String> context = MDC.getCopyOfContextMap();
            delegate.execute(() -> {
                Map<String, String> previous = MDC.getCopyOfContextMap();
                if (context == null) MDC.clear(); else MDC.setContextMap(context);
                active.incrementAndGet();
                try {
                    tasks.record(task);
                } finally {
                    active.decrementAndGet();
                    if (previous == null) MDC.clear(); else MDC.setContextMap(previous);
                }
            });
        }

        @Override public void shutdown() { delegate.shutdown(); }
        @Override public java.util.List<Runnable> shutdownNow() { return delegate.shutdownNow(); }
        @Override public boolean isShutdown() { return delegate.isShutdown(); }
        @Override public boolean isTerminated() { return delegate.isTerminated(); }
        @Override public boolean awaitTermination(long timeout, java.util.concurrent.TimeUnit unit)
                throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }
    }
}
