package com.broker.admin;

import de.codecentric.boot.admin.server.config.EnableAdminServer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The operations console: Spring Boot Admin (health, metrics, threads, log levels, scheduled jobs of every Spring service) and a Jobs page for stopping
 * rogue sync jobs. For a developer's laptop or an operator on a private network; never on a public address.
 */
@SpringBootApplication
@EnableAdminServer
public class AdminServiceApplication {
    public static void main(String[] args) { SpringApplication.run(AdminServiceApplication.class, args); }
}
