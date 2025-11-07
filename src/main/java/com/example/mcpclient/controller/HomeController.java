package com.example.mcpclient.controller;

import com.example.mcpclient.model.ChatSession;
import com.example.mcpclient.service.SessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

@Controller
@RequiredArgsConstructor
public class HomeController {

    private final SessionService sessionService;

    @GetMapping("/")
    public String index(Model model) {
        List<ChatSession> sessions = sessionService.getAllSessions();
        
        // Create a default session if none exists
        if (sessions.isEmpty()) {
            ChatSession defaultSession = sessionService.createSession("Chat 1");
            sessions = List.of(defaultSession);
        }

        model.addAttribute("sessions", sessions);
        return "index";
    }
}
