package com.example.mcpclient.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
public class ChatSession {
    private String id;
    private String name;
    private List<Message> messages;
    private LocalDateTime createdAt;
    private LocalDateTime lastActivity;

    public ChatSession() {
        this.id = UUID.randomUUID().toString();
        this.name = "New Chat";
        this.messages = new ArrayList<>();
        this.createdAt = LocalDateTime.now();
        this.lastActivity = LocalDateTime.now();
    }

    public ChatSession(String name) {
        this();
        this.name = name;
    }

    public void addMessage(Message message) {
        this.messages.add(message);
        this.lastActivity = LocalDateTime.now();
    }

    public void updateLastActivity() {
        this.lastActivity = LocalDateTime.now();
    }
}
