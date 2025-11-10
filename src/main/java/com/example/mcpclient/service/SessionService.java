package com.example.mcpclient.service;

import com.example.mcpclient.model.ChatSession;
import com.example.mcpclient.model.Message;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SessionService {

    private final Map<String, ChatSession> sessions = new ConcurrentHashMap<>();

    /**
     * Create a new chat session
     */
    public ChatSession createSession() {
        ChatSession session = new ChatSession();
        sessions.put(session.getId(), session);
        return session;
    }

    /**
     * Create a new chat session with a specific name
     */
    public ChatSession createSession(String name) {
        ChatSession session = new ChatSession(name);
        sessions.put(session.getId(), session);
        return session;
    }

    /**
     * Create a new chat session with a specific ID and name
     * This is useful when frontend has already generated a session ID
     */
    public ChatSession createSessionWithId(String sessionId, String name) {
        ChatSession session = new ChatSession(name);
        session.setId(sessionId);
        sessions.put(session.getId(), session);
        return session;
    }

    /**
     * Get a session by ID
     */
    public Optional<ChatSession> getSession(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    /**
     * Get all sessions
     */
    public List<ChatSession> getAllSessions() {
        return new ArrayList<>(sessions.values());
    }

    /**
     * Add a message to a session
     */
    public void addMessage(String sessionId, Message message) {
        ChatSession session = sessions.get(sessionId);
        if (session != null) {
            session.addMessage(message);
        }
    }

    /**
     * Delete a session
     */
    public boolean deleteSession(String sessionId) {
        return sessions.remove(sessionId) != null;
    }

    /**
     * Update session name
     */
    public boolean updateSessionName(String sessionId, String newName) {
        ChatSession session = sessions.get(sessionId);
        if (session != null) {
            session.setName(newName);
            return true;
        }
        return false;
    }

    /**
     * Clear all messages in a session
     */
    public boolean clearSession(String sessionId) {
        ChatSession session = sessions.get(sessionId);
        if (session != null) {
            session.getMessages().clear();
            session.updateLastActivity();
            return true;
        }
        return false;
    }
}