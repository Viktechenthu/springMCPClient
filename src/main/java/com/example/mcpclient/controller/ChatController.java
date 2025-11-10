package com.example.mcpclient.controller;

import com.example.mcpclient.dto.ChatRequest;
import com.example.mcpclient.dto.ChatResponse;
import com.example.mcpclient.model.ChatSession;
import com.example.mcpclient.model.McpTool;
import com.example.mcpclient.model.Message;
import com.example.mcpclient.service.McpClientService;
import com.example.mcpclient.service.OllamaService;
import com.example.mcpclient.service.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ChatController {

    private final SessionService sessionService;
    private final McpClientService mcpClientService;
    private final OllamaService ollamaService;

    /**
     * Send a chat message with streaming response using Ollama
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody ChatRequest request, HttpServletRequest req) {
        log.info("Received streaming chat request for session: {}", request.getSessionId());

        SseEmitter emitter = new SseEmitter(300000L); // 5 minute timeout

        Optional<ChatSession> sessionOpt = sessionService.getSession(request.getSessionId());
        if (sessionOpt.isEmpty()) {
            try {
                emitter.send(SseEmitter.event()
                        .name("error")
                        .data("{\"error\": \"Session not found\"}"));
                emitter.complete();
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
            return emitter;
        }

        ChatSession session = sessionOpt.get();
        String token = req.getHeader(HttpHeaders.AUTHORIZATION);
        if (token != null) {
            token = token.replace("Bearer ", "");
        }

        // Add user message to session
        Message userMessage = new Message("user", request.getMessage());
        session.addMessage(userMessage);

        // Send user message confirmation
        try {
            emitter.send(SseEmitter.event()
                    .name("userMessage")
                    .data("{\"id\": \"" + userMessage.getId() + "\"}"));
        } catch (IOException e) {
            log.error("Error sending user message event", e);
            emitter.completeWithError(e);
            return emitter;
        }

        // Generate a message ID for the assistant's response
        String assistantMessageId = UUID.randomUUID().toString();

        // Send message start event
        try {
            emitter.send(SseEmitter.event()
                    .name("start")
                    .data("{\"messageId\": \"" + assistantMessageId + "\"}"));
        } catch (IOException e) {
            log.error("Error sending start event", e);
            emitter.completeWithError(e);
            return emitter;
        }

        final String finalToken = token;

        // Process in background thread
        new Thread(() -> {
            try {
                StringBuilder fullResponse = new StringBuilder();

                // Stream response from Ollama
                ollamaService.chatStream(
                        request.getMessage(),
                        session.getMessages(),
                        finalToken,
                        chunk -> {
                            fullResponse.append(chunk);
                            try {
                                // Send each chunk to the client
                                emitter.send(SseEmitter.event()
                                        .name("chunk")
                                        .data("{\"content\": \"" + escapeJson(chunk) + "\"}"));
                            } catch (IOException e) {
                                log.error("Error sending chunk", e);
                                throw new RuntimeException(e);
                            }
                        }
                );

                // Add complete assistant message to session
                Message assistantMessage = new Message("assistant", fullResponse.toString());
                assistantMessage.setId(assistantMessageId);
                session.addMessage(assistantMessage);

                // Send completion event
                emitter.send(SseEmitter.event()
                        .name("done")
                        .data("{\"messageId\": \"" + assistantMessageId + "\"}"));

                emitter.complete();
                log.info("Streaming completed for session: {}", request.getSessionId());

            } catch (Exception e) {
                log.error("Error during streaming", e);
                try {
                    emitter.send(SseEmitter.event()
                            .name("error")
                            .data("{\"error\": \"" + escapeJson(e.getMessage()) + "\"}"));
                } catch (IOException ioException) {
                    log.error("Error sending error event", ioException);
                }
                emitter.completeWithError(e);
            }
        }).start();

        return emitter;
    }

    /**
     * Helper method to escape JSON strings
     */
    private String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                .replace("\b", "\\b")
                .replace("\f", "\\f");
    }

    /**
     * Create a new session
     */
    @PostMapping("/sessions")
    public ResponseEntity<ChatSession> createSession(@RequestBody(required = false) Map<String, String> body) {
        String name = (body != null && body.containsKey("name"))
                ? body.get("name")
                : "New Chat";
        ChatSession session = sessionService.createSession(name);
        return ResponseEntity.ok(session);
    }

    /**
     * Get all sessions
     */
    @GetMapping("/sessions")
    public ResponseEntity<List<ChatSession>> getAllSessions() {
        return ResponseEntity.ok(sessionService.getAllSessions());
    }

    /**
     * Get a specific session
     */
    @GetMapping("/sessions/{sessionId}")
    public ResponseEntity<ChatSession> getSession(@PathVariable String sessionId) {
        return sessionService.getSession(sessionId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Delete a session
     */
    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<Map<String, Boolean>> deleteSession(@PathVariable String sessionId) {
        boolean deleted = sessionService.deleteSession(sessionId);
        Map<String, Boolean> response = new HashMap<>();
        response.put("success", deleted);
        return ResponseEntity.ok(response);
    }

    /**
     * Update session name
     */
    @PutMapping("/sessions/{sessionId}/name")
    public ResponseEntity<Map<String, Boolean>> updateSessionName(
            @PathVariable String sessionId,
            @RequestBody Map<String, String> body) {
        String newName = body.get("name");
        boolean updated = sessionService.updateSessionName(sessionId, newName);
        Map<String, Boolean> response = new HashMap<>();
        response.put("success", updated);
        return ResponseEntity.ok(response);
    }

    /**
     * Clear session messages
     */
    @DeleteMapping("/sessions/{sessionId}/messages")
    public ResponseEntity<Map<String, Boolean>> clearSession(@PathVariable String sessionId) {
        boolean cleared = sessionService.clearSession(sessionId);
        Map<String, Boolean> response = new HashMap<>();
        response.put("success", cleared);
        return ResponseEntity.ok(response);
    }

    /**
     * Health check
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "UP");

        Boolean mcpServerHealthy = mcpClientService.checkServerHealth().block();
        health.put("mcpServer", mcpServerHealthy ? "UP" : "DOWN");
        health.put("aiProvider", "Ollama");

        return ResponseEntity.ok(health);
    }

    /**
     * Get available tools from MCP server
     */
    @GetMapping("/tools")
    public ResponseEntity<?> getTools() {
        try {
            log.debug("Fetching tools from MCP server");
            List<McpTool> toolsList = mcpClientService.listTools().block();
            log.info("Retrieved {} tools from MCP server", toolsList != null ? toolsList.size() : 0);
            return ResponseEntity.ok(toolsList != null ? toolsList : Collections.emptyList());
        } catch (Exception e) {
            log.error("Error fetching tools from MCP server", e);
            return ResponseEntity.ok(Collections.emptyList());
        }
    }

    /**
     * Provide feedback on a message
     */
    @PostMapping("/feedback")
    public ResponseEntity<ChatResponse> provideFeedback(
            @RequestBody Map<String, Object> feedbackRequest) {
        try {
            String sessionId = (String) feedbackRequest.get("sessionId");
            String messageId = (String) feedbackRequest.get("messageId");
            Boolean liked = (Boolean) feedbackRequest.get("liked");

            log.info("Received feedback for session: {}, message: {}, liked: {}",
                    sessionId, messageId, liked);

            Optional<ChatSession> sessionOpt = sessionService.getSession(sessionId);
            if (sessionOpt.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(ChatResponse.error("Session not found"));
            }

            ChatSession session = sessionOpt.get();
            Optional<Message> messageOpt = session.getMessages().stream()
                    .filter(msg -> msg.getId().equals(messageId))
                    .findFirst();

            if (messageOpt.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(ChatResponse.error("Message not found"));
            }

            Message message = messageOpt.get();
            message.setLiked(liked);

            log.info("Updated message feedback: {} -> {}", messageId, liked);
            return ResponseEntity.ok(ChatResponse.success(message));
        } catch (Exception e) {
            log.error("Error recording feedback", e);
            return ResponseEntity.internalServerError()
                    .body(ChatResponse.error("Error recording feedback: " + e.getMessage()));
        }
    }
}