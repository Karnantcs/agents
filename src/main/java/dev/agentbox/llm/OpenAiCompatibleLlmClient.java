package dev.agentbox.llm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.agentbox.AgentboxProperties;
import dev.agentbox.http.HttpTransport;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class OpenAiCompatibleLlmClient implements LlmClient {

    private static final Duration TIMEOUT = Duration.ofMinutes(3);

    private final HttpTransport http;
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final int maxTokens;

    public OpenAiCompatibleLlmClient(HttpTransport http, ObjectMapper mapper, AgentboxProperties properties) {
        this.http = http;
        this.mapper = mapper;
        this.baseUrl = trimSlash(properties.llm().openaiBaseUrl());
        String key = properties.llm().openaiApiKey();
        this.apiKey = key == null || key.isBlank() ? "not-set" : key;
        this.model = properties.llm().openaiModel();
        this.maxTokens = properties.llm().maxTokens();
    }

    @Override
    public LlmTurn complete(String system, List<ChatMessage> messages, List<ToolSpec> tools) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        ArrayNode messageNodes = body.putArray("messages");
        messageNodes.addObject().put("role", "system").put("content", system);
        for (ChatMessage message : messages) {
            if (message instanceof ChatMessage.UserText user) {
                messageNodes.addObject().put("role", "user").put("content", user.text());
            } else if (message instanceof ChatMessage.AssistantTurn assistant) {
                if (!(assistant.turn().replay() instanceof ObjectNode replay)) {
                    throw new IllegalStateException("OpenAI history is missing the original assistant message");
                }
                messageNodes.add(replay.deepCopy());
            } else if (message instanceof ChatMessage.ToolResult result) {
                messageNodes.addObject()
                        .put("role", "tool")
                        .put("tool_call_id", result.toolCallId())
                        .put("content", result.output());
            }
        }
        ArrayNode toolNodes = body.putArray("tools");
        for (ToolSpec tool : tools) {
            ObjectNode function = toolNodes.addObject().put("type", "function").putObject("function");
            function.put("name", tool.name());
            function.put("description", tool.description());
            function.set("parameters", mapper.valueToTree(tool.inputSchema()));
        }

        HttpTransport.Result response;
        try {
            response = http.exchange(
                    "POST",
                    URI.create(baseUrl + "/chat/completions"),
                    mapper.writeValueAsString(body),
                    Map.of("authorization", "Bearer " + apiKey),
                    TIMEOUT);
        } catch (Exception exception) {
            throw new IllegalStateException("OpenAI-compatible request failed: " + exception.getMessage(), exception);
        }
        if (response.status() >= 400) {
            throw new IllegalStateException("OpenAI-compatible request failed: HTTP " + response.status() + " "
                    + clip(response.body()));
        }
        return parse(response.body());
    }

    private LlmTurn parse(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            JsonNode message = root.path("choices").path(0).path("message");
            if (message.isMissingNode() || message.isNull()) {
                throw new IllegalStateException("OpenAI-compatible response had no message");
            }
            JsonNode content = message.get("content");
            String text = content == null || content.isNull() ? null : content.asText();
            List<ToolCall> calls = new ArrayList<>();
            for (JsonNode call : message.path("tool_calls")) {
                String id = call.path("id").asText("");
                String name = call.path("function").path("name").asText("");
                calls.add(new ToolCall(id, name, arguments(call.path("function").path("arguments"))));
            }
            return new LlmTurn(text, calls, message.deepCopy());
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Could not read the model response: " + exception.getMessage(), exception);
        }
    }

    private Map<String, Object> arguments(JsonNode node) {
        try {
            if (node.isObject()) {
                return mapper.convertValue(node, new TypeReference<Map<String, Object>>() {});
            }
            String raw = node.isMissingNode() || node.isNull() ? "" : node.asText();
            if (raw.isBlank()) {
                return Map.of();
            }
            Map<String, Object> parsed = mapper.readValue(raw, new TypeReference<Map<String, Object>>() {});
            return parsed == null ? Map.of() : parsed;
        } catch (Exception exception) {
            return Map.of("_invalid_json", node.isTextual() ? node.asText() : node.toString());
        }
    }

    private static String trimSlash(String url) {
        if (url == null || url.isBlank()) {
            return "https://api.openai.com/v1";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String clip(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
