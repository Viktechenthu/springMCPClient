package com.example.mcpclient.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfig {

    /**
     * ChatClient with tool callbacks for non-streaming requests
     */
    @Bean
    public ChatClient chatClient(ChatClient.Builder chatClientBuilder, ToolCallbackProvider tools) {
        return chatClientBuilder.defaultToolCallbacks(tools).build();
    }

    /**
     * Expose ChatModel bean for direct streaming access
     * This is already auto-configured by Spring AI, we just make it explicit here
     */
    @Bean
    public ChatModel chatModel(ChatModel chatModel) {
        return chatModel;
    }
}