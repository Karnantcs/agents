package dev.agentbox;

import java.time.Duration;

public final class TestAgents {

    private TestAgents() {}

    public static AgentboxProperties properties(String provider, String anthropicKey) {
        return new AgentboxProperties(
                "orchestrator",
                "agentbox-agent:latest",
                "agentbox_default",
                "http://orchestrator:8091",
                8092,
                536_870_912L,
                1_000_000_000L,
                Duration.ZERO,
                Duration.ofSeconds(30),
                4,
                8,
                "/workspace",
                "agent",
                "You are a helpful agent.",
                new AgentboxProperties.Llm(
                        provider,
                        1024,
                        "claude-sonnet-5-5",
                        anthropicKey,
                        "sk-test",
                        "https://example.test/v1",
                        "gpt-test"));
    }
}
