package com.wan.downloadmanager.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * In-process publish/subscribe for live updates. Topics mirror the WebSocket endpoints:
 * {@code downloads}, {@code download-speed}, {@code media-detection}, {@code notifications}, {@code conversion}.
 * A failing subscriber never affects the publisher.
 */
@Component
public class EventBus {
    private static final Logger log = LoggerFactory.getLogger(EventBus.class);
    public static final String DOWNLOADS = "downloads";
    public static final String SPEED = "download-speed";
    public static final String MEDIA = "media-detection";
    public static final String NOTIFICATIONS = "notifications";
    public static final String CONVERSION = "conversion";

    private final Map<String, CopyOnWriteArrayList<Consumer<Object>>> subscribers = new ConcurrentHashMap<>();

    public Runnable subscribe(String topic, Consumer<Object> subscriber) {
        subscribers.computeIfAbsent(topic, t -> new CopyOnWriteArrayList<>()).add(subscriber);
        return () -> subscribers.getOrDefault(topic, new CopyOnWriteArrayList<>()).remove(subscriber);
    }

    public void publish(String topic, Object payload) {
        for (Consumer<Object> s : subscribers.getOrDefault(topic, new CopyOnWriteArrayList<>())) {
            try {
                s.accept(payload);
            } catch (RuntimeException e) {
                log.debug("Subscriber of {} failed: {}", topic, e.toString());
            }
        }
    }

    /** User-facing notification (desktop toast / notification centre). */
    public record Notice(String level, String title, String message, String subjectId, long at) {}

    public void notify(String level, String title, String message, String subjectId) {
        publish(NOTIFICATIONS, new Notice(level, title, message, subjectId, System.currentTimeMillis()));
    }
}
