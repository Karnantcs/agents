package dev.agentbox.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.DirectCaller;
import com.anthropic.models.messages.CacheCreation;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.ThinkingBlock;
import com.anthropic.models.messages.ToolUseBlock;
import com.anthropic.models.messages.Usage;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentbox.TestAgents;
import dev.agentbox.http.HttpTransport;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class LlmClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void openAiClientParsesToolCallsAndReplaysThem() throws Exception {
        AtomicReference<String> secondBody = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpTransport http = new HttpTransport() {
            @Override
            public Result exchange(String method, URI uri, String body, Duration timeout) {
                throw new AssertionError("expected the client to pass request headers");
            }

            @Override
            public Result exchange(String method, URI uri, String body, Map<String, String> headers, Duration timeout) {
                assertThat(uri).isEqualTo(URI.create("https://example.test/v1/chat/completions"));
                assertThat(method).isEqualTo("POST");
                authorization.set(headers.get("authorization"));
                if (secondBody.get() == null && body.contains("\"role\":\"tool\"")) {
                    secondBody.set(body);
                }
                if (body.contains("\"role\":\"tool\"")) {
                    return new Result(200, """
                            {"choices":[{"message":{"role":"assistant","content":"worker said hi"}}]}
                            """);
                }
                return new Result(200, """
                        {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
                          {"id":"call_1","type":"function","function":{"name":"message_agent","arguments":"{\\"name\\":\\"worker\\",\\"message\\":\\"hi\\"}"}}
                        ]}}]}
                        """);
            }
        };
        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(
                http, mapper, TestAgents.properties("openai", ""));
        LlmTurn first = client.complete("be brief", List.of(new ChatMessage.UserText("delegate")), List.of());
        assertThat(first.calls()).hasSize(1);
        assertThat(first.calls().get(0).name()).isEqualTo("message_agent");
        assertThat(first.calls().get(0).arguments()).containsEntry("name", "worker");
        assertThat(authorization.get()).isEqualTo("Bearer sk-test");

        LlmTurn second = client.complete(
                "be brief",
                List.of(
                        new ChatMessage.UserText("delegate"),
                        new ChatMessage.AssistantTurn(first),
                        new ChatMessage.ToolResult("call_1", "done")),
                List.of());
        assertThat(second.text()).isEqualTo("worker said hi");
        assertThat(secondBody.get()).contains("\"tool_call_id\":\"call_1\"");
        assertThat(secondBody.get()).contains("message_agent");
    }

    @Test
    void openAiClientReportsHttpErrors() {
        HttpTransport http = (method, uri, body, timeout) -> new HttpTransport.Result(401, "bad key");
        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(
                http, mapper, TestAgents.properties("openai", ""));
        assertThatThrownBy(() -> client.complete("s", List.of(new ChatMessage.UserText("hi")), List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("401");
    }

    @Test
    void anthropicClientParsesToolUseAndReplaysThinking() {
        AtomicReference<MessageCreateParams> captured = new AtomicReference<>();
        Message toolMessage = message(
                ContentBlock.ofThinking(ThinkingBlock.builder()
                        .thinking("need the shell")
                        .signature("sig")
                        .build()),
                ContentBlock.ofToolUse(ToolUseBlock.builder()
                        .id("toolu_1")
                        .name("run_shell")
                        .caller(DirectCaller.builder().build())
                        .input(JsonValue.from(Map.of("command", "echo hi")))
                        .build()),
                StopReason.TOOL_USE);
        AnthropicLlmClient client = new AnthropicLlmClient("claude-sonnet-5-5", 1024, params -> {
            if (captured.get() == null && params.messages().size() == 1) {
                return toolMessage;
            }
            captured.set(params);
            return message(
                    ContentBlock.ofText(TextBlock.builder()
                            .text("all done")
                            .citations(Optional.empty())
                            .build()),
                    StopReason.END_TURN);
        });

        LlmTurn first = client.complete("system", List.of(new ChatMessage.UserText("run it")), List.of(shellSpec()));
        assertThat(first.calls()).singleElement().satisfies(call -> {
            assertThat(call.id()).isEqualTo("toolu_1");
            assertThat(call.name()).isEqualTo("run_shell");
            assertThat(call.arguments()).containsEntry("command", "echo hi");
        });

        LlmTurn second = client.complete(
                "system",
                List.of(
                        new ChatMessage.UserText("run it"),
                        new ChatMessage.AssistantTurn(first),
                        new ChatMessage.ToolResult("toolu_1", "hi")),
                List.of(shellSpec()));
        assertThat(second.text()).isEqualTo("all done");
        List<ContentBlockParam> assistant = captured.get().messages().get(1).content().asBlockParams();
        assertThat(assistant).anyMatch(ContentBlockParam::isThinking);
        assertThat(assistant).anyMatch(ContentBlockParam::isToolUse);
        assertThat(captured.get().messages().get(2).content().asBlockParams()).anyMatch(ContentBlockParam::isToolResult);
        assertThat(captured.get().messages().get(1).role().asString()).isEqualTo("assistant");
    }

    @Test
    void missingAnthropicKeyFailsBeforeARequest() {
        var client = LlmClients.create(TestAgents.properties("anthropic", ""), new ObjectMapper(), unusedHttp());
        assertThatThrownBy(() -> client.complete("s", List.of(new ChatMessage.UserText("hi")), List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ANTHROPIC_API_KEY");
    }

    @Test
    void unknownProviderIsRejected() {
        assertThatThrownBy(() -> LlmClients.create(TestAgents.properties("nope", ""), new ObjectMapper(), unusedHttp()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LLM_PROVIDER");
    }

    private static HttpTransport unusedHttp() {
        return (method, uri, body, timeout) -> {
            throw new AssertionError("http");
        };
    }

    private static ToolSpec shellSpec() {
        return new ToolSpec(
                "run_shell",
                "run a command",
                Map.of("type", "object", "properties", Map.of("command", Map.of("type", "string")), "required", List.of("command")));
    }

    private static Message message(ContentBlock block, StopReason stop) {
        return message(block, null, stop);
    }

    private static Message message(ContentBlock first, ContentBlock second, StopReason stop) {
        Message.Builder builder = Message.builder()
                .id("msg_test")
                .model("claude-sonnet-5-5")
                .role(JsonValue.from("assistant"))
                .container(Optional.empty())
                .diagnostics(Optional.empty())
                .stopDetails(Optional.empty())
                .stopSequence(Optional.empty())
                .stopReason(stop)
                .usage(Usage.builder()
                        .inputTokens(1)
                        .outputTokens(1)
                        .cacheCreationInputTokens(0)
                        .cacheReadInputTokens(0)
                        .inferenceGeo(Optional.empty())
                        .outputTokensDetails(Optional.empty())
                        .serverToolUse(Optional.empty())
                        .serviceTier(Optional.empty())
                        .cacheCreation(CacheCreation.builder()
                                .ephemeral5mInputTokens(0)
                                .ephemeral1hInputTokens(0)
                                .build())
                        .build());
        if (second == null) {
            builder.content(List.of(first));
        } else {
            builder.content(List.of(first, second));
        }
        return builder.build();
    }
}
