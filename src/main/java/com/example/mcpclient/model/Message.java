package com.example.mcpclient.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Message {
    private String id;
    private String role; // "user" or "assistant"
    private String content;
    private LocalDateTime timestamp;
    private Boolean liked; // null = no feedback, true = thumbs up, false = thumbs down

    public Message(String role, String content) {
        this.role = role;
        this.content = content;
        this.timestamp = LocalDateTime.now();
        this.id = java.util.UUID.randomUUID().toString();
        this.liked = null; // No feedback by default
    }
}
