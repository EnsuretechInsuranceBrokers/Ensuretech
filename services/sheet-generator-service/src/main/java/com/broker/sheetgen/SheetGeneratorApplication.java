package com.broker.sheetgen;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SheetGeneratorApplication {
    public static void main(String[] args) {
        SpringApplication.run(SheetGeneratorApplication.class, args);
    }
}
