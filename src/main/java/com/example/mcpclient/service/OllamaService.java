package com.example.mcpclient.service;

import com.example.mcpclient.model.Message;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OllamaService {

    @Autowired
    private final ChatClient chatClient;

    /**
     * Send a message to Ollama and get a response
     */
    public String chat(String userMessage, List<Message> chatHistory, String token) {
        log.debug("Sending message to Ollama: {}", userMessage);

        try {
            // Convert chat history to Spring AI message format
            List<org.springframework.ai.chat.messages.Message> messages = new ArrayList<>();

            // Add system message
            messages.add(new SystemMessage("""
                You are a Clinical AI Assistant.
                Display results in proper clinical format.
                Retrieve patient data from a clinician’s perspective.
                Provide details (e.g., progress notes, care plan) one at a time.
                Summarize all data when asked for a patient summary.
                Do not refetch unless explicitly requested.
                Never ask the clinician to use APIs or databases.
                Do not show the Organization ID.
                """));
            messages.add(new SystemMessage("Use this new JWT token when calling the apis. Do not prompt the user for a JWT. "+ token));

            // Add chat history
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

            // Create prompt and get response
            Prompt prompt = new Prompt(messages);
            String response = chatClient.prompt(prompt).call().content();
            
            log.debug("Received response from Ollama: {}", response);
            return response;
            
        } catch (Exception e) {
            log.error("Error communicating with Ollama", e);
            return "Error: Unable to communicate with Ollama - " + e.getMessage();
        }
    }
}
