package com.wan.downloadmanager.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wan.downloadmanager.notification.EventBus;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live topics at {@code /ws/<topic>} (server → client JSON). Authentication happens in
 * {@link com.wan.downloadmanager.security.LocalApiFilter} before the handshake; no browser origin is allowed.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final EventBus bus;
    private final ObjectMapper mapper;

    public WebSocketConfig(EventBus bus, ObjectMapper mapper) {
        this.bus = bus;
        this.mapper = mapper;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        for (String topic : new String[]{EventBus.DOWNLOADS, EventBus.SPEED, EventBus.MEDIA, EventBus.NOTIFICATIONS, EventBus.CONVERSION}) {
            registry.addHandler(new TopicHandler(topic), "/ws/" + topic);
        }
    }

    private final class TopicHandler extends TextWebSocketHandler {
        private final String topic;
        private final Map<String, Runnable> unsubscribe = new ConcurrentHashMap<>();

        TopicHandler(String topic) {
            this.topic = topic;
        }

        @Override
        public void afterConnectionEstablished(WebSocketSession raw) {
            WebSocketSession session = new ConcurrentWebSocketSessionDecorator(raw, 5000, 512 * 1024);
            unsubscribe.put(raw.getId(), bus.subscribe(topic, payload -> {
                try {
                    if (session.isOpen()) session.sendMessage(new TextMessage(mapper.writeValueAsString(payload)));
                } catch (Exception ignored) {
                    // slow or closed client: dropped message
                }
            }));
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            Runnable r = unsubscribe.remove(session.getId());
            if (r != null) r.run();
        }
    }
}
