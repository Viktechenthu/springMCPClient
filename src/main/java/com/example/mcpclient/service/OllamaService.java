package com.example.mcpclient.service;

import com.example.mcpclient.model.McpTool;
import com.example.mcpclient.model.Message;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class OllamaService {

    private final ChatClient chatClient;
    private final ChatClient.Builder chatClientBuilder;
    private final McpClientService mcpClientService;
    private final ObjectMapper objectMapper;

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
     * Send a message to Ollama and stream the response with MCP tool integration
     */
    public void chatStream(String userMessage, List<Message> chatHistory, String token, Consumer<String> chunkConsumer) {
        log.debug("Sending streaming message to Ollama with MCP tool support: {}", userMessage);

        try {
            // First, check if we need to call any MCP tools
            String toolResult = tryCallMcpTool(userMessage);

            if (toolResult != null) {
                // A tool was called, now ask Ollama to format the response
                List<org.springframework.ai.chat.messages.Message> messages = buildMessagesWithToolResult(
                        userMessage, toolResult, chatHistory, token
                );
                Prompt prompt = new Prompt(messages);

                ChatClient streamingClient = chatClientBuilder.build();

                Flux<String> contentStream = streamingClient.prompt(prompt)
                        .stream()
                        .content()
                        .filter(content -> content != null && !content.isEmpty());

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
                        .blockLast();
            } else {
                // No tool needed, regular chat
                List<org.springframework.ai.chat.messages.Message> messages = buildMessages(userMessage, chatHistory, token);
                Prompt prompt = new Prompt(messages);

                ChatClient streamingClient = chatClientBuilder.build();

                Flux<String> contentStream = streamingClient.prompt(prompt)
                        .stream()
                        .content()
                        .filter(content -> content != null && !content.isEmpty());

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
                        .blockLast();
            }

        } catch (Exception e) {
            log.error("Error communicating with Ollama", e);
            chunkConsumer.accept("Error: Unable to communicate with Ollama - " + e.getMessage());
        }
    }

    /**
     * Try to call an MCP tool based on the user's message
     */
    private String tryCallMcpTool(String userMessage) {
        try {
            String lowerMessage = userMessage.toLowerCase();

            // Get all patients
            if (lowerMessage.contains("all patients") || lowerMessage.contains("list patients") ||
                    lowerMessage.contains("show patients") || lowerMessage.contains("get patients")) {
                log.info("Detected request for all patients");
                return mcpClientService.callTool("get_all_patients", new HashMap<>()).block();
            }

            // Get patient by name
            Pattern namePattern = Pattern.compile("patient.*?(?:named?|called?)\\s+([A-Z][a-z]+(?:\\s+[A-Z][a-z]+)+)", Pattern.CASE_INSENSITIVE);
            Matcher nameMatcher = namePattern.matcher(userMessage);
            if (nameMatcher.find()) {
                String patientName = nameMatcher.group(1);
                log.info("Detected request for patient by name: {}", patientName);
                Map<String, Object> args = new HashMap<>();
                args.put("name", patientName);
                return mcpClientService.callTool("get_patient_by_name", args).block();
            }

            // Get patient by ID
            Pattern idPattern = Pattern.compile("patient\\s+(?:id|#)?\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
            Matcher idMatcher = idPattern.matcher(userMessage);
            if (idMatcher.find()) {
                Long patientId = Long.parseLong(idMatcher.group(1));
                log.info("Detected request for patient by ID: {}", patientId);
                Map<String, Object> args = new HashMap<>();
                args.put("patient_id", patientId);
                return mcpClientService.callTool("get_patient_by_id", args).block();
            }

            // Get progress notes
            if (lowerMessage.contains("progress note") || lowerMessage.contains("notes for")) {
                Pattern notesIdPattern = Pattern.compile("(?:patient\\s+(?:id\\s+)?)?(?:#)?(\\d+)", Pattern.CASE_INSENSITIVE);
                Matcher notesIdMatcher = notesIdPattern.matcher(userMessage);
                if (notesIdMatcher.find()) {
                    Long patientId = Long.parseLong(notesIdMatcher.group(1));
                    log.info("Detected request for progress notes for patient: {}", patientId);
                    Map<String, Object> args = new HashMap<>();
                    args.put("patient_id", patientId);
                    return mcpClientService.callTool("get_progress_notes", args).block();
                }
            }

            // Get care plan
            if (lowerMessage.contains("care plan")) {
                Pattern carePlanPattern = Pattern.compile("(?:patient\\s+(?:id\\s+)?)?(?:#)?(\\d+)", Pattern.CASE_INSENSITIVE);
                Matcher carePlanMatcher = carePlanPattern.matcher(userMessage);
                if (carePlanMatcher.find()) {
                    Long patientId = Long.parseLong(carePlanMatcher.group(1));
                    log.info("Detected request for care plan for patient: {}", patientId);
                    Map<String, Object> args = new HashMap<>();
                    args.put("patient_id", patientId);
                    return mcpClientService.callTool("get_care_plan", args).block();
                }
            }

        } catch (Exception e) {
            log.error("Error trying to call MCP tool", e);
        }

        return null;
    }

    /**
     * Build messages with tool result for Ollama to format
     */
    private List<org.springframework.ai.chat.messages.Message> buildMessagesWithToolResult(
            String userMessage, String toolResult, List<Message> chatHistory, String token) {

        List<org.springframework.ai.chat.messages.Message> messages = new ArrayList<>();

        // Add system message with tool context
        messages.add(new SystemMessage("""
                You are a helpful AI Assistant with access to a patient care system.
                
                The user asked: "%s"
                
                I retrieved this data from the patient care system:
                %s
                
                Please format this information in a clear, professional, and easy-to-read manner.
                - Present the data in a structured format
                - Highlight key information
                - Use proper formatting (bold, lists, tables where appropriate)
                - Be conversational and helpful
                - If the data shows an error, explain it clearly to the user
                
                Do not mention that you're formatting data or that you received JSON.
                Just present the information naturally as if you retrieved it yourself.
                """.formatted(userMessage, toolResult)));

        // Add chat history for context
        if (chatHistory != null && !chatHistory.isEmpty()) {
            // Only include recent history (last 3 exchanges)
            int startIndex = Math.max(0, chatHistory.size() - 6);
            List<Message> recentHistory = chatHistory.subList(startIndex, chatHistory.size());

            messages.addAll(recentHistory.stream()
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
        messages.add(new UserMessage("Please format and present this information clearly."));

        return messages;
    }

    /**
     * Build the message list for Ollama (regular chat without tools)
     */
    private List<org.springframework.ai.chat.messages.Message> buildMessages(
            String userMessage, List<Message> chatHistory, String token) {

        List<org.springframework.ai.chat.messages.Message> messages = new ArrayList<>();

        // Get available tools for context
        List<McpTool> tools = mcpClientService.listTools().block();
        String toolsDescription = formatToolsForPrompt(tools);

        // Add system message
        messages.add(new SystemMessage("""
                You are a helpful AI Assistant with access to a patient care system through MCP tools.
                
                %s
                
                When users ask about patients, you can tell them what information is available.
                Be helpful and guide them on how to ask questions about patients.
                
                Important: You cannot directly call these tools - when a user asks for patient data,
                I will retrieve it for you and you will format the response.
                """.formatted(toolsDescription)));

        // Add optional token info if provided
        if (token != null && !token.isEmpty()) {
            messages.add(new SystemMessage("Authentication token is available for secure operations."));
        }

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

        return messages;
    }

    /**
     * Format available tools for the prompt
     */
    private String formatToolsForPrompt(List<McpTool> tools) {
        if (tools == null || tools.isEmpty()) {
            return "No tools are currently available.";
        }

        StringBuilder sb = new StringBuilder("Available patient care tools:\n\n");
        for (McpTool tool : tools) {
            sb.append("- **").append(tool.getName()).append("**: ")
                    .append(tool.getDescription()).append("\n");
        }

        return sb.toString();
    }
}