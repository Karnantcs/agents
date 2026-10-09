package dev.agentbox.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.fasterxml.jackson.core.type.TypeReference;
import dev.agentbox.AgentboxProperties;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Claude tool loop. Assistant turns are replayed with {@link Message#toParam()} so thinking
 * blocks come back unchanged, which current Claude models require during tool use.
 */
public final class AnthropicLlmClient implements LlmClient {

    @FunctionalInterface
    public interface Caller {
        Message create(MessageCreateParams params);
    }

    private final String model;
    private final long maxTokens;
    private final Caller caller;

    public AnthropicLlmClient(String model, long maxTokens, Caller caller) {
        this.model = model;
        this.maxTokens = maxTokens;
        this.caller = caller;
    }

    public static AnthropicLlmClient fromProperties(AgentboxProperties properties) {
        String apiKey = properties.llm().anthropicApiKey();
        AtomicReference<AnthropicClient> cached = new AtomicReference<>();
        return new AnthropicLlmClient(
                properties.llm().anthropicModel(),
                properties.llm().maxTokens(),
                params -> {
                    if (apiKey == null || apiKey.isBlank()) {
                        throw new IllegalStateException("ANTHROPIC_API_KEY is not set");
                    }
                    AnthropicClient sdk = cached.get();
                    if (sdk == null) {
                        synchronized (cached) {
                            sdk = cached.get();
                            if (sdk == null) {
                                sdk = AnthropicOkHttpClient.builder().apiKey(apiKey).build();
                                cached.set(sdk);
                            }
                        }
                    }
                    return sdk.messages().create(params);
                });
    }

    @Override
    public LlmTurn complete(String system, List<ChatMessage> messages, List<ToolSpec> tools) {
        MessageCreateParams.Builder request = MessageCreateParams.builder()
                .model(model)
                .maxTokens(maxTokens)
                .system(system);
        for (ToolSpec tool : tools) {
            request.addTool(toTool(tool));
        }
        List<ChatMessage.ToolResult> pendingResults = new ArrayList<>();
        for (ChatMessage message : messages) {
            if (message instanceof ChatMessage.ToolResult result) {
                pendingResults.add(result);
                continue;
            }
            flushResults(request, pendingResults);
            if (message instanceof ChatMessage.UserText user) {
                request.addUserMessage(user.text());
            } else if (message instanceof ChatMessage.AssistantTurn assistant) {
                if (!(assistant.turn().replay() instanceof Message replay)) {
                    throw new IllegalStateException("Anthropic history is missing the original assistant message");
                }
                request.addMessage(replay);
            }
        }
        flushResults(request, pendingResults);

        Message response = caller.create(request.build());
        return toTurn(response);
    }

    private static void flushResults(MessageCreateParams.Builder request, List<ChatMessage.ToolResult> pending) {
        if (pending.isEmpty()) {
            return;
        }
        List<ContentBlockParam> blocks = new ArrayList<>();
        for (ChatMessage.ToolResult result : pending) {
            blocks.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                    .toolUseId(result.toolCallId())
                    .content(result.output())
                    .build()));
        }
        request.addUserMessageOfBlockParams(blocks);
        pending.clear();
    }

    private static LlmTurn toTurn(Message response) {
        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        for (ContentBlock block : response.content()) {
            block.text().ifPresent(textBlock -> {
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                text.append(textBlock.text());
            });
            block.toolUse().ifPresent(tool -> calls.add(toCall(tool)));
        }
        String reply = text.isEmpty() ? null : text.toString();
        return new LlmTurn(reply, calls, response);
    }

    private static ToolCall toCall(ToolUseBlock tool) {
        Map<String, Object> arguments;
        try {
            Map<String, Object> converted = tool._input().convert(new TypeReference<Map<String, Object>>() {});
            arguments = converted == null ? Map.of() : converted;
        } catch (RuntimeException exception) {
            arguments = Map.of("_invalid_json", String.valueOf(tool._input()));
        }
        return new ToolCall(tool.id(), tool.name(), arguments);
    }

    private static Tool toTool(ToolSpec spec) {
        Map<String, JsonValue> properties = new LinkedHashMap<>();
        Object rawProperties = spec.inputSchema().get("properties");
        if (rawProperties instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                properties.put(String.valueOf(entry.getKey()), JsonValue.from(entry.getValue()));
            }
        }
        Tool.InputSchema.Builder schema = Tool.InputSchema.builder()
                .type(JsonValue.from("object"))
                .properties(Tool.InputSchema.Properties.builder()
                        .additionalProperties(properties)
                        .build());
        Object required = spec.inputSchema().get("required");
        if (required instanceof List<?> names) {
            for (Object name : names) {
                schema.addRequired(String.valueOf(name));
            }
        }
        return Tool.builder()
                .name(spec.name())
                .description(spec.description())
                .inputSchema(schema.build())
                .build();
    }
}
