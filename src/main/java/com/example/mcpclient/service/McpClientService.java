package com.example.mcpclient.service;

import com.example.mcpclient.model.Message;
import com.example.mcpclient.model.McpTool;
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

/**
 * MCP Client Service - Connects to patient-care-system MCP server using JSON-RPC 2.0
 */
@Slf4j
@Service
public class McpClientService {

    private final WebClient webClient;
    private final long timeout;
    private final String mcpEndpoint;

    public McpClientService(
            @Value("${mcp.server.url}") String mcpServerUrl,
            @Value("${mcp.server.timeout:30000}") long timeout) {
        this.timeout = timeout;
        this.mcpEndpoint = mcpServerUrl;

        // Extract base URL (remove /mcp if present)
        String baseUrl = mcpServerUrl;
        if (baseUrl.endsWith("/mcp")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 4);
        }

        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();

        log.info("MCP Client initialized with URL: {}", mcpServerUrl);
    }

    /**
     * Initialize the MCP connection
     */
    public Mono<Boolean> initialize() {
        log.debug("Initializing MCP connection");

        Map<String, Object> request = new HashMap<>();
        request.put("jsonrpc", "2.0");
        request.put("method", "initialize");
        request.put("id", 1);

        return webClient.post()
                .uri("/mcp")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofMillis(timeout))
                .map(response -> {
                    log.debug("MCP initialized: {}", response);
                    return true;
                })
                .doOnError(error -> log.error("Error initializing MCP", error))
                .onErrorReturn(false);
    }

    /**
     * Check if MCP server is available
     */
    public Mono<Boolean> checkServerHealth() {
        return initialize();
    }

    /**
     * List available tools from MCP server using JSON-RPC 2.0
     */
    public Mono<List<McpTool>> listTools() {
        log.debug("Fetching available tools from MCP server");

        Map<String, Object> request = new HashMap<>();
        request.put("jsonrpc", "2.0");
        request.put("method", "tools/list");
        request.put("id", 2);

        return webClient.post()
                .uri("/mcp")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofSeconds(10))
                .<List<McpTool>>map(response -> {
                    log.debug("Received response from MCP server: {}", response);

                    Object resultObj = response.get("result");
                    if (resultObj instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> result = (Map<String, Object>) resultObj;
                        Object toolsObj = result.get("tools");

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

    /**
     * Call a tool on the MCP server using JSON-RPC 2.0
     */
    public Mono<String> callTool(String toolName, Map<String, Object> arguments) {
        log.debug("Calling tool: {} with arguments: {}", toolName, arguments);

        Map<String, Object> params = new HashMap<>();
        params.put("name", toolName);
        params.put("arguments", arguments != null ? arguments : new HashMap<>());

        Map<String, Object> request = new HashMap<>();
        request.put("jsonrpc", "2.0");
        request.put("method", "tools/call");
        request.put("params", params);
        request.put("id", 3);

        return webClient.post()
                .uri("/mcp")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofMillis(timeout))
                .map(response -> {
                    log.debug("Tool call response: {}", response);

                    Object resultObj = response.get("result");
                    if (resultObj instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> result = (Map<String, Object>) resultObj;

                        Object contentObj = result.get("content");
                        if (contentObj instanceof List) {
                            @SuppressWarnings("unchecked")
                            List<Map<String, Object>> content = (List<Map<String, Object>>) contentObj;

                            if (!content.isEmpty()) {
                                Map<String, Object> firstContent = content.get(0);
                                Object text = firstContent.get("text");
                                return text != null ? text.toString() : "No response";
                            }
                        }
                    }

                    Object errorObj = response.get("error");
                    if (errorObj != null) {
                        return "Error: " + errorObj.toString();
                    }

                    return "No response from tool";
                })
                .doOnError(error -> log.error("Error calling tool {}", toolName, error))
                .onErrorResume(error -> {
                    return Mono.just("Error: Unable to call tool - " + error.getMessage());
                });
    }

    /**
     * Send a message to the MCP server and get a response (not used in current implementation)
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
                    Object responseObj = response.get("response");
                    return responseObj != null ? responseObj.toString() : "No response from server";
                })
                .doOnError(error -> log.error("Error communicating with MCP server", error))
                .onErrorResume(error -> {
                    return Mono.just("Error: Unable to communicate with MCP server - " + error.getMessage());
                });
    }
}