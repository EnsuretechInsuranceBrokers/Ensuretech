package com.broker.sheetgen;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class Config {
    @Bean
    Clock clock() { return Clock.systemDefaultZone(); }
}
