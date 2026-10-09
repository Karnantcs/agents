package dev.agentbox.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentbox.AgentboxProperties;
import dev.agentbox.http.HttpTransport;
import java.util.Locale;

public final class LlmClients {

    private LlmClients() {}

    public static LlmClient create(AgentboxProperties properties, ObjectMapper mapper, HttpTransport http) {
        String provider = properties.llm().provider() == null
                ? ""
                : properties.llm().provider().trim().toLowerCase(Locale.ROOT);
        return switch (provider) {
            case "anthropic" -> AnthropicLlmClient.fromProperties(properties);
            case "openai" -> new OpenAiCompatibleLlmClient(http, mapper, properties);
            default -> throw new IllegalStateException(
                    "Unknown LLM_PROVIDER '" + provider + "'. Use anthropic or openai.");
        };
    }
}
