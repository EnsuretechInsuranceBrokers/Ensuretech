package com.broker.integration.outbox;

import java.util.concurrent.TimeUnit;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
class KafkaEventSender implements EventSender {
    private final KafkaTemplate<String, String> kafka;

    KafkaEventSender(KafkaTemplate<String, String> kafka) {
        this.kafka = kafka;
    }

    @Override
    public void send(String topic, String key, String jsonPayload) throws Exception {
        kafka.send(topic, key, jsonPayload).get(10, TimeUnit.SECONDS);
    }
}
