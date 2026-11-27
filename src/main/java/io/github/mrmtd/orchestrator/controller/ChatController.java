package io.github.mrmtd.orchestrator.controller;

import io.github.mrmtd.orchestrator.model.ChatReply;
import io.github.mrmtd.orchestrator.model.ChatRequest;
import io.github.mrmtd.orchestrator.service.ChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code POST /api/v1/chat}: the only endpoint the front end needs. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Chat", description = "Ask about loads, carriers and delayed shipments in plain words")
public class ChatController {

    static final String REQUEST_EXAMPLE = """
            { "message": "Which loads from Texas are running late?" }""";

    static final String REPLY_EXAMPLE = """
            {
              "conversationId": "7a35d7b7-936b-41f9-addc-22ccb431565b",
              "answer": "Two loads from Texas are running late: LD-000101 (9.5 h, Swift Freight) and LD-000102 (4.5 h, Swift Freight).",
              "model": "gpt-5-mini",
              "toolCalls": [
                { "tool": "findDelayedShipments", "arguments": "{\\"originState\\":\\"TX\\"}", "outcome": "success", "durationMs": 42 }
              ],
              "usage": { "promptTokens": 812, "completionTokens": 96, "totalTokens": 908 },
              "durationMs": 2140
            }""";

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping("/chat")
    @Operation(summary = "Send a chat message",
            description = """
                    The model reads the MCP server's tools, calls the ones it needs, and answers from the \
                    results. Send back `conversationId` from the reply to continue the conversation.""",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(schema = @Schema(implementation = ChatRequest.class),
                            examples = @ExampleObject(name = "Late loads", value = REQUEST_EXAMPLE))))
    @ApiResponse(responseCode = "200", description = "The answer, the tool calls behind it, and token usage",
            content = @Content(schema = @Schema(implementation = ChatReply.class),
                    examples = @ExampleObject(name = "Late loads", value = REPLY_EXAMPLE)))
    @ApiResponse(responseCode = "400", description = "Blank or too long message",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "502", description = "The model call failed",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "The MCP server is unreachable",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    public ChatReply chat(@Valid @RequestBody ChatRequest request) {
        return chatService.chat(request.message(), request.conversationId());
    }
}
