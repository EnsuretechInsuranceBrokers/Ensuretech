package com.broker.org.outbox;

/** Transport abstraction so the relay can be tested without Kafka. Must block until acked or throw. */
public interface EventSender {
    void send(String topic, String key, String jsonPayload) throws Exception;
}
