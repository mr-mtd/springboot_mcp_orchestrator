package io.github.mrmtd.orchestrator;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code POST /api/v1/chat}: the only endpoint the front end needs. */
@RestController
@RequestMapping("/api/v1")
class ChatController {

    /**
     * @param message        what the user typed
     * @param conversationId send back the id from the previous reply to continue that conversation;
     *                       leave it out to start a new one
     */
    record ChatRequest(@NotBlank @Size(max = 4000) String message, @Nullable @Size(max = 100) String conversationId) {
    }

    private final ChatService chat;

    ChatController(ChatService chat) {
        this.chat = chat;
    }

    @PostMapping("/chat")
    ChatService.ChatReply chat(@Valid @RequestBody ChatRequest request) {
        return chat.chat(request.message(), request.conversationId());
    }
}
