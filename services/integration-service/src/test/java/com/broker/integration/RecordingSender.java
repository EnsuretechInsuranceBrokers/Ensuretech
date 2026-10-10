package com.broker.integration;

import com.broker.integration.outbox.EventSender;
import java.util.ArrayList;
import java.util.List;

class RecordingSender implements EventSender {
    record Sent(String topic, String key, String payload) {}

    final List<Sent> sent = new ArrayList<>();
    volatile boolean failing = false;

    @Override
    public void send(String topic, String key, String payload) {
        if (failing) throw new IllegalStateException("broker down");
        sent.add(new Sent(topic, key, payload));
    }
}
