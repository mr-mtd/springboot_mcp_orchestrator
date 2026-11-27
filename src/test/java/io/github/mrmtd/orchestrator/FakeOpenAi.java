package io.github.mrmtd.orchestrator;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * A stand-in for the OpenAI Chat Completions API. Each request to {@code /v1/chat/completions} gets
 * the next scripted response, and the request bodies are kept so tests can check what was sent.
 */
final class FakeOpenAi implements AutoCloseable {

    private final HttpServer server;
    private final Deque<String> responses = new ArrayDeque<>();
    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());

    FakeOpenAi() {
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String body;
            synchronized (responses) {
                body = responses.pollFirst();
            }
            int status = body == null ? 500 : 200;
            byte[] bytes = (body == null ? "{\"error\":{\"message\":\"no scripted response\"}}" : body)
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort() + "/v1";
    }

    void reset() {
        responses.clear();
        requests.clear();
    }

    List<String> requests() {
        return List.copyOf(requests);
    }

    /** The model asks to call {@code tool} with {@code argumentsJson}. */
    FakeOpenAi thenToolCall(String tool, String argumentsJson) {
        String args = argumentsJson.replace("\\", "\\\\").replace("\"", "\\\"");
        responses.add("""
                {"id":"chatcmpl-1","object":"chat.completion","created":1764230400,"model":"gpt-5-mini",
                 "choices":[{"index":0,"finish_reason":"tool_calls","message":{"role":"assistant","content":null,
                   "tool_calls":[{"id":"call_%d","type":"function","function":{"name":"%s","arguments":"%s"}}]}}],
                 "usage":{"prompt_tokens":100,"completion_tokens":20,"total_tokens":120}}
                """.formatted(responses.size(), tool, args));
        return this;
    }

    /** The model answers with {@code text}. */
    FakeOpenAi thenAnswer(String text) {
        responses.add("""
                {"id":"chatcmpl-2","object":"chat.completion","created":1764230401,"model":"gpt-5-mini",
                 "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"%s"}}],
                 "usage":{"prompt_tokens":150,"completion_tokens":30,"total_tokens":180}}
                """.formatted(text));
        return this;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
