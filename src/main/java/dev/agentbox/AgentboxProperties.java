package dev.agentbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agentbox")
public record AgentboxProperties(
        String mode,
        String agentImage,
        String dockerNetwork,
        String orchestratorUrl,
        int agentPort,
        long memLimitBytes,
        long nanoCpus,
        Duration readyTimeout,
        Duration taskTimeout,
        int maxHops,
        int maxSteps,
        String workspace,
        String agentName,
        String role,
        Llm llm) {

    public record Llm(
            String provider,
            int maxTokens,
            String anthropicModel,
            String anthropicApiKey,
            String openaiApiKey,
            String openaiBaseUrl,
            String openaiModel) {}
}
