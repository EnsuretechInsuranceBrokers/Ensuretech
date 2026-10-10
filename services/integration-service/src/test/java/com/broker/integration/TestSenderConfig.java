package com.broker.integration;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
class TestSenderConfig {
    @Bean @Primary RecordingSender recordingSender() { return new RecordingSender(); }
}
