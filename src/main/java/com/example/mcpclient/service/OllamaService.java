package com.example.mcpclient.service;

import com.example.mcpclient.model.McpTool;
import com.example.mcpclient.model.Message;
import com.fasterxml.jackson.core.type.TypeReference;
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
            // First, ask LLM if it needs to call any tools
            ToolDecision decision = decideTool(userMessage);

            if (decision != null && decision.shouldCallTool()) {
                log.info("LLM decided to call tool: {} with arguments: {}", decision.toolName, decision.arguments);

                // Call the tool
                String toolResult = mcpClientService.callTool(decision.toolName, decision.arguments).block();

                // Now ask Ollama to format the response
                streamFormattedResponse(userMessage, toolResult, chatHistory, token, chunkConsumer);
            } else {
                // No tool needed, regular chat
                log.debug("No tool call needed, proceeding with regular chat");
                streamRegularResponse(userMessage, chatHistory, token, chunkConsumer);
            }

        } catch (Exception e) {
            log.error("Error communicating with Ollama", e);
            chunkConsumer.accept("Error: Unable to communicate with Ollama - " + e.getMessage());
        }
    }

    /**
     * Ask the LLM to decide if a tool should be called
     */
    private ToolDecision decideTool(String userMessage) {
        try {
            List<McpTool> tools = mcpClientService.listTools().block();
            if (tools == null || tools.isEmpty()) {
                return null;
            }

            String prompt = buildToolDecisionPrompt(userMessage, tools);

            List<org.springframework.ai.chat.messages.Message> messages = Arrays.asList(
                    new SystemMessage("You are a tool selection assistant. Respond ONLY with valid JSON."),
                    new UserMessage(prompt)
            );

            Prompt chatPrompt = new Prompt(messages);

            String llmResponse = chatClient.prompt(chatPrompt)
                    .call()
                    .content();

            log.debug("Tool decision response: {}", llmResponse);

            return parseToolDecision(llmResponse);

        } catch (Exception e) {
            log.error("Error in tool decision", e);
            return null;
        }
    }

    /**
     * Build prompt for tool decision
     */
    private String buildToolDecisionPrompt(String userMessage, List<McpTool> tools) {
        StringBuilder sb = new StringBuilder();
        sb.append("Available tools:\n");

        for (McpTool tool : tools) {
            sb.append("{\n");
            sb.append("  \"name\": \"").append(tool.getName()).append("\",\n");
            sb.append("  \"description\": \"").append(tool.getDescription()).append("\",\n");
            sb.append("  \"parameters\": ").append(formatParameters(tool.getInputSchema())).append("\n");
            sb.append("}\n\n");
        }

        sb.append("User request: \"").append(userMessage).append("\"\n\n");
        sb.append("Analyze the request and respond with ONLY a JSON object:\n\n");
        sb.append("To call a tool:\n");
        sb.append("{\"action\": \"call\", \"tool\": \"tool_name\", \"arguments\": {...}}\n\n");
        sb.append("If no tool needed:\n");
        sb.append("{\"action\": \"none\"}\n\n");
        sb.append("Extract values from the user's message. Use numbers for IDs, strings for names.\n");
        sb.append("Return ONLY the JSON, no explanation:");

        return sb.toString();
    }

    /**
     * Format parameters from schema
     */
    private String formatParameters(Object schema) {
        if (schema == null) {
            return "{}";
        }
        try {
            if (schema instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> schemaMap = (Map<String, Object>) schema;
                Object props = schemaMap.get("properties");
                if (props != null) {
                    return objectMapper.writeValueAsString(props);
                }
            }
            return "{}";
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * Parse tool decision from LLM
     */
    private ToolDecision parseToolDecision(String response) {
        if (response == null || response.trim().isEmpty()) {
            return null;
        }

        try {
            // Clean response
            String cleaned = response.trim()
                    .replaceAll("```json\\s*", "")
                    .replaceAll("```\\s*", "")
                    .trim();

            // Extract JSON
            int start = cleaned.indexOf('{');
            int end = cleaned.lastIndexOf('}');

            if (start >= 0 && end > start) {
                cleaned = cleaned.substring(start, end + 1);
            }

            log.debug("Parsing tool decision JSON: {}", cleaned);

            Map<String, Object> json = objectMapper.readValue(cleaned, new TypeReference<Map<String, Object>>() {});

            String action = (String) json.get("action");

            if (!"call".equalsIgnoreCase(action)) {
                return null;
            }

            String toolName = (String) json.get("tool");

            @SuppressWarnings("unchecked")
            Map<String, Object> arguments = (Map<String, Object>) json.getOrDefault("arguments", new HashMap<>());

            return new ToolDecision(toolName, arguments);

        } catch (Exception e) {
            log.warn("Failed to parse tool decision: {}", response, e);
            return null;
        }
    }

    /**
     * Stream formatted response with tool result
     */
    private void streamFormattedResponse(String userMessage, String toolResult,
                                         List<Message> chatHistory, String token,
                                         Consumer<String> chunkConsumer) {
        List<org.springframework.ai.chat.messages.Message> messages = new ArrayList<>();

        messages.add(new SystemMessage(String.format("""
                The user asked: "%s"
                
                Retrieved data:
                %s
                
                Format this information clearly and professionally.
                Present it in a natural, conversational way.
                Use formatting (headings, lists, tables) where helpful.
                Do not mention JSON or technical details.
                """, userMessage, toolResult)));

        if (chatHistory != null && !chatHistory.isEmpty()) {
            int start = Math.max(0, chatHistory.size() - 4);
            chatHistory.subList(start, chatHistory.size()).forEach(msg -> {
                if ("user".equals(msg.getRole())) {
                    messages.add(new UserMessage(msg.getContent()));
                } else {
                    messages.add(new org.springframework.ai.chat.messages.AssistantMessage(msg.getContent()));
                }
            });
        }

        messages.add(new UserMessage("Please present this information."));

        streamResponse(messages, chunkConsumer);
    }

    /**
     * Stream regular response without tools
     */
    private void streamRegularResponse(String userMessage, List<Message> chatHistory,
                                       String token, Consumer<String> chunkConsumer) {
        List<org.springframework.ai.chat.messages.Message> messages = buildMessages(userMessage, chatHistory, token);
        streamResponse(messages, chunkConsumer);
    }

    /**
     * Stream response helper
     */
    private void streamResponse(List<org.springframework.ai.chat.messages.Message> messages,
                                Consumer<String> chunkConsumer) {
        Prompt prompt = new Prompt(messages);
        ChatClient streamingClient = chatClientBuilder.build();

        Flux<String> contentStream = streamingClient.prompt(prompt)
                .stream()
                .content()
                .filter(content -> content != null && !content.isEmpty());

        contentStream
                .doOnNext(chunk -> {
                    log.trace("Chunk: {}", chunk);
                    chunkConsumer.accept(chunk);
                })
                .doOnError(error -> {
                    log.error("Streaming error", error);
                    chunkConsumer.accept("\n\n[Error: " + error.getMessage() + "]");
                })
                .doOnComplete(() -> log.debug("Stream complete"))
                .blockLast();
    }

    /**
     * Build messages for regular chat
     */
    private List<org.springframework.ai.chat.messages.Message> buildMessages(
            String userMessage, List<Message> chatHistory, String token) {

        List<org.springframework.ai.chat.messages.Message> messages = new ArrayList<>();

        List<McpTool> tools = mcpClientService.listTools().block();
        String toolsDescription = formatToolsForPrompt(tools);

        messages.add(new SystemMessage("""
                You are a helpful AI assistant with access to a patient care system.
                
                %s
                
                When users ask about patient data, I will retrieve it for you automatically.
                Your job is to have natural conversations and present information clearly.
                """.formatted(toolsDescription)));

        if (token != null && !token.isEmpty()) {
            messages.add(new SystemMessage("Authentication is available."));
        }

        if (chatHistory != null && !chatHistory.isEmpty()) {
            chatHistory.forEach(msg -> {
                if ("user".equals(msg.getRole())) {
                    messages.add(new UserMessage(msg.getContent()));
                } else {
                    messages.add(new org.springframework.ai.chat.messages.AssistantMessage(msg.getContent()));
                }
            });
        }

        messages.add(new UserMessage(userMessage));

        return messages;
    }

    /**
     * Format tools for prompt
     */
    private String formatToolsForPrompt(List<McpTool> tools) {
        if (tools == null || tools.isEmpty()) {
            return "No tools available.";
        }

        StringBuilder sb = new StringBuilder("Available capabilities:\n\n");
        for (McpTool tool : tools) {
            sb.append("- ").append(tool.getDescription()).append("\n");
        }

        return sb.toString();
    }

    /**
     * Tool decision holder
     */
    private static class ToolDecision {
        String toolName;
        Map<String, Object> arguments;

        ToolDecision(String toolName, Map<String, Object> arguments) {
            this.toolName = toolName;
            this.arguments = arguments;
        }

        boolean shouldCallTool() {
            return toolName != null && !toolName.isEmpty();
        }
    }
}