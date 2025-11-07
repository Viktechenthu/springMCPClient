package com.example.mcpclient.dto;

import com.example.mcpclient.model.Message;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatResponse {
    private boolean success;
    private Message message;
    private String error;

    public static ChatResponse success(Message message) {
        return new ChatResponse(true, message, null);
    }

    public static ChatResponse error(String error) {
        return new ChatResponse(false, null, error);
    }
}
