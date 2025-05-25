package com.uber.api.customer.service.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

@Slf4j
public class CustomerWebSocketHandler extends TextWebSocketHandler {

    private static final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String customerEmail = getCustomerEmailFromSession(session);
        sessions.put(customerEmail, session);
        log.info("WebSocket connection established for customer: {}", customerEmail);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        String customerEmail = getCustomerEmailFromSession(session);
        sessions.remove(customerEmail);
        log.info("WebSocket connection closed for customer: {}", customerEmail);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        log.info("Received WebSocket message: {}", message.getPayload());
        // Handle incoming messages if needed
    }

    public static void sendMessageToCustomer(String customerEmail, String message) {
        WebSocketSession session = sessions.get(customerEmail);
        if (session != null && session.isOpen()) {
            try {
                session.sendMessage(new TextMessage(message));
                log.info("Sent WebSocket message to customer {}: {}", customerEmail, message);
            } catch (Exception e) {
                log.error("Error sending WebSocket message to customer: {}", customerEmail, e);
            }
        }
    }

    private String getCustomerEmailFromSession(WebSocketSession session) {
        // In a real application, extract from JWT token or session attributes
        // For now, use a simple approach
        return session.getUri().getQuery(); // Expecting ?customerEmail=email
    }
}
