package dev.agentbox.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentbox.llm.ChatMessage;
import dev.agentbox.llm.LlmClient;
import dev.agentbox.llm.LlmTurn;
import dev.agentbox.llm.ToolCall;
import dev.agentbox.llm.ToolSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentLoopTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void returnsTextWhenTheModelDoesNotCallATool() {
        ScriptedLlm llm = new ScriptedLlm(new LlmTurn("done", List.of(), "replay"));
        TaskResult result = new AgentLoop(llm, 4).run("system", "task", unusedTools());
        assertThat(result.reply()).isEqualTo("done");
        assertThat(result.steps()).isEqualTo(1);
        assertThat(result.trace()).isEmpty();
    }

    @Test
    void runsAToolThenReturnsTheFinalAnswer(@TempDir Path workspace) throws Exception {
        ScriptedLlm llm = new ScriptedLlm(
                new LlmTurn(
                        null,
                        List.of(new ToolCall("call-1", "write_file", Map.of("path", "hello.txt", "content", "hi"))),
                        "replay-1"),
                new LlmTurn("wrote it", List.of(), "replay-2"));
        ToolCatalog tools = catalog(workspace);
        TaskResult result = new AgentLoop(llm, 8).run(Prompts.system("file clerk"), "write hello", tools);
        assertThat(result.reply()).isEqualTo("wrote it");
        assertThat(result.steps()).isEqualTo(2);
        assertThat(result.trace()).extracting(TaskResult.TraceStep::tool).containsExactly("write_file");
        assertThat(Files.readString(workspace.resolve("hello.txt"))).isEqualTo("hi");
        assertThat(llm.systems.get(0)).contains("file clerk");
        assertThat(llm.systems.get(0)).contains("private workspace");
        assertThat(llm.systems.get(0)).contains("rely on that agent's reply");
    }

    @Test
    void feedsToolErrorsBackToTheModel() {
        ScriptedLlm llm = new ScriptedLlm(
                new LlmTurn(null, List.of(new ToolCall("call-1", "missing", Map.of())), "replay"),
                new LlmTurn("could not do that", List.of(), "replay-2"));
        TaskResult result = new AgentLoop(llm, 4).run("system", "task", unusedTools());
        assertThat(result.reply()).isEqualTo("could not do that");
        assertThat(result.trace().get(0).output()).contains("unknown tool");
    }

    @Test
    void stopsAtTheStepLimit() {
        LlmTurn tool = new LlmTurn(null, List.of(new ToolCall("call", "missing", Map.of())), "replay");
        ScriptedLlm llm = new ScriptedLlm(tool, tool);
        TaskResult result = new AgentLoop(llm, 2).run("system", "task", unusedTools());
        assertThat(result.steps()).isEqualTo(2);
        assertThat(result.reply()).contains("Stopped after 2 steps");
    }

    private ToolCatalog catalog(Path workspace) {
        return new ToolCatalog(
                workspace,
                "http://orchestrator:8091",
                0,
                Duration.ofSeconds(5),
                Duration.ofSeconds(20),
                (method, uri, body, timeout) -> {
                    throw new AssertionError("no http");
                },
                mapper);
    }

    private ToolCatalog unusedTools() {
        return new ToolCatalog(
                Path.of("unused"),
                "http://orchestrator:8091",
                0,
                Duration.ofSeconds(1),
                Duration.ofSeconds(20),
                (method, uri, body, timeout) -> {
                    throw new AssertionError("no http");
                },
                mapper);
    }

    private static final class ScriptedLlm implements LlmClient {
        private final Queue<LlmTurn> turns = new ArrayDeque<>();
        private final List<String> systems = new ArrayList<>();

        private ScriptedLlm(LlmTurn... script) {
            turns.addAll(List.of(script));
        }

        @Override
        public LlmTurn complete(String system, List<ChatMessage> messages, List<ToolSpec> tools) {
            systems.add(system);
            return turns.remove();
        }
    }
}
