package io.github.mrmtd.orchestrator.controller;

import io.github.mrmtd.orchestrator.model.ChatReply;
import io.github.mrmtd.orchestrator.model.ChatRequest;
import io.github.mrmtd.orchestrator.service.ChatService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code POST /api/v1/chat}: the only endpoint the front end needs. */
@RestController
@RequestMapping("/api/v1")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping("/chat")
    public ChatReply chat(@Valid @RequestBody ChatRequest request) {
        return chatService.chat(request.message(), request.conversationId());
    }
}
