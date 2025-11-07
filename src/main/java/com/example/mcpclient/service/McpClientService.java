package com.example.mcpclient.service;

import com.example.mcpclient.model.Message;
import com.example.mcpclient.model.McpTool;
import io.modelcontextprotocol.client.McpClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class McpClientService {

    private final WebClient webClient;
    private final long timeout;
    private final String endpoint;


    public McpClientService(
            @Value("${mcp.server.url}") String mcpServerUrl,
            @Value("${mcp.server.endpoint:/sse}") String endpoint,
            @Value("${mcp.server.timeout}") long timeout) {
        this.timeout = timeout;
        this.endpoint = endpoint;
        this.webClient = WebClient.builder()
                .baseUrl(mcpServerUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }


    /**
     * Send a message to the MCP server and get a response
     */
    public Mono<String> sendMessage(String userMessage, List<Message> chatHistory) {
        log.debug("Sending message to MCP server: {}", userMessage);

        Map<String, Object> request = new HashMap<>();
        request.put("message", userMessage);
        request.put("history", chatHistory);

        return webClient.post()
                .uri("/chat")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofMillis(timeout))
                .map(response -> {
                    // Extract the response message from the MCP server response
                    Object responseObj = response.get("response");
                    return responseObj != null ? responseObj.toString() : "No response from server";
                })
                .doOnError(error -> log.error("Error communicating with MCP server", error))
                .onErrorResume(error -> {
                    // Fallback response if MCP server is unavailable
                    return Mono.just("Error: Unable to communicate with MCP server - " + error.getMessage());
                });
    }

    /**
     * Simple echo response for testing without MCP server
     */
    public Mono<String> getEchoResponse(String userMessage) {
        return Mono.just("Echo: " + userMessage);
    }

    /**
     * Mock response for development/testing
     */
    public Mono<String> getMockResponse(String userMessage) {
        String response = switch (userMessage.toLowerCase()) {
            case "hello", "hi" -> "Hello! How can I assist you today?";
            case "help" -> "I'm here to help! You can ask me anything about the Model Context Protocol.";
            case "bye", "goodbye" -> "Goodbye! Have a great day!";
            default -> "I received your message: \"" + userMessage + "\". This is a mock response. " +
                    "Configure the MCP server URL in application.properties to get real responses.";
        };
        return Mono.just(response);
    }

    /**
     * Check if MCP server is available
     */
    public Mono<Boolean> checkServerHealth() {
        return webClient.get()
                .uri("/health")
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(5))
                .map(response -> true)
                .onErrorResume(error -> Mono.just(false));
    }

    /**
     * List available tools from MCP server
     */
    public Mono<List<McpTool>> listTools() {
        log.debug("Fetching available tools from MCP server at endpoint: {}", endpoint);

        return webClient.get()
                .uri(endpoint)  // Use configured endpoint
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofSeconds(10))
                .<List<McpTool>>map(response -> {
                    log.debug("Received response from MCP server: {}", response);
                    // Extract tools from response
                    Object toolsObj = response.get("tools");
                    if (toolsObj instanceof List) {
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> toolsList = (List<Map<String, Object>>) toolsObj;
                        log.info("Successfully loaded {} tools from MCP server", toolsList.size());
                        return toolsList.stream()
                                .map(toolMap -> new McpTool(
                                        (String) toolMap.get("name"),
                                        (String) toolMap.get("description"),
                                        toolMap.get("inputSchema")
                                ))
                                .collect(Collectors.toList());
                    }
                    log.warn("No tools found in MCP server response");
                    return Collections.emptyList();
                })
                .doOnError(error -> log.error("Error fetching tools from MCP server", error))
                .onErrorResume(error -> {
                    log.warn("Could not fetch tools from MCP server: {}", error.getMessage());
                    return Mono.just(Collections.emptyList());
                });
    }
}
