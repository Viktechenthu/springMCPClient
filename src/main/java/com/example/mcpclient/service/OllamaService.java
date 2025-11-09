package com.example.mcpclient.service;

import com.example.mcpclient.model.Message;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

@Slf4j
@Service
@RequiredArgsConstructor
public class OllamaService {

    private final ChatClient chatClient;
    private final ChatClient.Builder chatClientBuilder;

    /**
     * Send a message to Ollama and get a response (non-streaming)
     */
    public String chat(String userMessage, List<Message> chatHistory, String token) {
        log.debug("Sending message to Ollama: {}", userMessage);

        try {
            List<org.springframework.ai.chat.messages.Message> messages = buildMessages(userMessage, chatHistory, token);
            Prompt prompt = new Prompt(messages);

            String response = chatClient.prompt(prompt)
                    .stream()
                    .content()
                    .collectList()
                    .map(list -> String.join("", list))
                    .block();

            log.debug("Received response from Ollama: {}", response);
            return response;

        } catch (Exception e) {
            log.error("Error communicating with Ollama", e);
            return "Error: Unable to communicate with Ollama - " + e.getMessage();
        }
    }

    /**
     * Send a message to Ollama and stream the response
     * @param userMessage The user's message
     * @param chatHistory Previous chat history
     * @param token JWT token for authentication (may be null/unused for Ollama)
     * @param chunkConsumer Consumer that receives each chunk of the response
     */
    public void chatStream(String userMessage, List<Message> chatHistory, String token, Consumer<String> chunkConsumer) {
        log.debug("Sending streaming message to Ollama: {}", userMessage);

        try {
            List<org.springframework.ai.chat.messages.Message> messages = buildMessages(userMessage, chatHistory, token);
            Prompt prompt = new Prompt(messages);

            // Create a ChatClient without advisors for streaming
            ChatClient streamingClient = chatClientBuilder.build();

            // Stream the response
            Flux<String> contentStream = streamingClient.prompt(prompt)
                    .stream()
                    .content()
                    .filter(content -> content != null && !content.isEmpty());

            // Subscribe to the stream and process each chunk
            contentStream
                    .doOnNext(chunk -> {
                        log.trace("Received chunk from Ollama: {}", chunk);
                        chunkConsumer.accept(chunk);
                    })
                    .doOnError(error -> {
                        log.error("Error during streaming from Ollama", error);
                        chunkConsumer.accept("\n\n[Error: " + error.getMessage() + "]");
                    })
                    .doOnComplete(() -> log.debug("Streaming completed from Ollama"))
                    .blockLast(); // Wait for the stream to complete

        } catch (Exception e) {
            log.error("Error communicating with Ollama", e);
            chunkConsumer.accept("Error: Unable to communicate with Ollama - " + e.getMessage());
        }
    }

    /**
     * Build the message list for Ollama
     */
    private List<org.springframework.ai.chat.messages.Message> buildMessages(
            String userMessage, List<Message> chatHistory, String token) {

        List<org.springframework.ai.chat.messages.Message> messages = new ArrayList<>();

        // Add system message
        messages.add(new SystemMessage("""
                You are a helpful AI Assistant.
                Provide clear, concise, and accurate responses.
                When appropriate, format your responses with proper structure.
                """));

        // Add optional token info if provided
        if (token != null && !token.isEmpty()) {
            messages.add(new SystemMessage("Authentication token is available for secure operations."));
        }

        // Add chat history (excluding the current user message if it's already in history)
        if (chatHistory != null && !chatHistory.isEmpty()) {
            messages.addAll(chatHistory.stream()
                    .map(msg -> {
                        if ("user".equals(msg.getRole())) {
                            return new UserMessage(msg.getContent());
                        } else {
                            return new org.springframework.ai.chat.messages.AssistantMessage(msg.getContent());
                        }
                    })
                    .toList());
        }

        // Add current user message
        messages.add(new UserMessage(userMessage));

        return messages;
    }
}