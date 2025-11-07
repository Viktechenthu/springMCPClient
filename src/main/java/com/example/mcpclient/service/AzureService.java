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
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

@Slf4j
@Service
@RequiredArgsConstructor
public class AzureService {

  @Autowired
  private final ChatClient chatClient;

  private final ChatClient.Builder chatClientBuilder;

  /**
   * Send a message to Azure OpenAI and get a response (non-streaming - kept for backward compatibility)
   */
  public String chat(String userMessage, List<Message> chatHistory, String token) {
    log.debug("Sending message to Azure: {}", userMessage);

    try {
      List<org.springframework.ai.chat.messages.Message> messages = buildMessages(userMessage, chatHistory, token);
      Prompt prompt = new Prompt(messages);

      String response = chatClient.prompt(prompt)
          .stream()
          .content()
          .collectList()
          .map(list -> String.join("", list))
          .block();

      log.debug("Received response from Azure: {}", response);
      return response;

    } catch (Exception e) {
      log.error("Error communicating with azure", e);
      return "Error: Unable to communicate with azure - " + e.getMessage();
    }
  }

  /**
   * Send a message to Azure OpenAI and stream the response
   * @param userMessage The user's message
   * @param chatHistory Previous chat history
   * @param token JWT token for authentication
   * @param chunkConsumer Consumer that receives each chunk of the response
   */
  public void chatStream(String userMessage, List<Message> chatHistory, String token, Consumer<String> chunkConsumer) {
    log.debug("Sending streaming message to Azure: {}", userMessage);

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
            log.trace("Received chunk from Azure: {}", chunk);
            chunkConsumer.accept(chunk);
          })
          .doOnError(error -> {
            log.error("Error during streaming from Azure", error);
            chunkConsumer.accept("\n\n[Error: " + error.getMessage() + "]");
          })
          .doOnComplete(() -> log.debug("Streaming completed from Azure"))
          .blockLast(); // Wait for the stream to complete

    } catch (Exception e) {
      log.error("Error communicating with azure", e);
      chunkConsumer.accept("Error: Unable to communicate with azure - " + e.getMessage());
    }
  }

  /**
   * Build the message list for Azure OpenAI
   */
  private List<org.springframework.ai.chat.messages.Message> buildMessages(
      String userMessage, List<Message> chatHistory, String token) {

    List<org.springframework.ai.chat.messages.Message> messages = new ArrayList<>();

    // Add system messages
    messages.add(new SystemMessage("""
                You are a Clinical AI Assistant.
                Display results in proper clinical format.
                Retrieve patient data from a clinician's perspective.
                Provide details (e.g., progress notes, care plan) one at a time.
                Summarize all data when asked for a patient summary.
                Do not refetch unless explicitly requested.
                Never ask the clinician to use APIs or databases.
                Do not show the Organization ID.
                """));
    messages.add(new SystemMessage("Use this new JWT token when calling the apis. Do not prompt the user for a JWT. " + token));

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