package dev.agentbox;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AgentboxProperties.class)
public class AgentboxApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentboxApplication.class, args);
    }
}
