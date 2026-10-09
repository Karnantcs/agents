package dev.agentbox.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentbox.http.HttpTransport;
import dev.agentbox.llm.ToolCall;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ToolCatalogTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void writesAndReadsInsideTheWorkspace(@TempDir Path workspace) {
        ToolCatalog tools = catalog(workspace, (method, uri, body, timeout) -> new HttpTransport.Result(500, ""));
        String written = tools.execute(new ToolCall("1", "write_file", Map.of("path", "notes/a.txt", "content", "hello")));
        assertThat(written).contains("notes/a.txt");
        assertThat(tools.execute(new ToolCall("2", "read_file", Map.of("path", "notes/a.txt")))).isEqualTo("hello");
    }

    @Test
    void rejectsPathsOutsideTheWorkspace(@TempDir Path workspace) {
        ToolCatalog tools = catalog(workspace, (method, uri, body, timeout) -> new HttpTransport.Result(500, ""));
        assertThat(tools.execute(new ToolCall("1", "read_file", Map.of("path", "../secret")))).contains("outside the workspace");
        assertThat(tools.execute(new ToolCall("2", "write_file", Map.of("path", "/etc/passwd", "content", "x"))))
                .contains("outside the workspace");
    }

    @Test
    void shellRunsInTheWorkspaceAndDoesNotInheritHostSecrets(@TempDir Path workspace) throws Exception {
        ToolCatalog tools = catalog(workspace, (method, uri, body, timeout) -> new HttpTransport.Result(500, ""));
        assertThat(tools.execute(new ToolCall("1", "run_shell", Map.of("command", "pwd")))).contains(workspace.toString());
        String env = tools.execute(new ToolCall("2", "run_shell", Map.of("command", "env")));
        assertThat(env).contains("WORKSPACE=");
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.isBlank()) {
            assertThat(env).doesNotContain(javaHome);
        }
        assertThat(env).doesNotContain("ANTHROPIC_API_KEY");
        assertThat(tools.execute(new ToolCall("3", "run_shell", Map.of("command", "sleep 5"))))
                .contains("timed out");
        assertThat(Files.exists(workspace)).isTrue();
    }

    @Test
    void messageAgentPostsTheNextHop(@TempDir Path workspace) throws Exception {
        AtomicReference<URI> seenUri = new AtomicReference<>();
        AtomicReference<String> seenBody = new AtomicReference<>();
        ToolCatalog tools = catalog(workspace, (method, uri, body, timeout) -> {
            seenUri.set(uri);
            seenBody.set(body);
            return new HttpTransport.Result(200, "{\"reply\":\"worker finished\"}");
        });
        String reply = tools.execute(new ToolCall(
                "1", "message_agent", Map.of("name", "worker", "message", "write the file")));
        assertThat(reply).isEqualTo("worker finished");
        assertThat(seenUri.get()).isEqualTo(URI.create("http://orchestrator:8091/agents/worker/message"));
        JsonNode body = mapper.readTree(seenBody.get());
        assertThat(body.get("message").asText()).isEqualTo("write the file");
        assertThat(body.get("hops").asInt()).isEqualTo(2);
    }

    private ToolCatalog catalog(Path workspace, HttpTransport http) {
        return new ToolCatalog(
                workspace, "http://orchestrator:8091", 1, Duration.ofSeconds(5), Duration.ofMillis(200), http, mapper);
    }
}
