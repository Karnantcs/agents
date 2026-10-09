package dev.agentbox.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentbox.http.HttpTransport;
import dev.agentbox.llm.ToolCall;
import dev.agentbox.llm.ToolSpec;
import dev.agentbox.orchestrator.Names;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ToolCatalog {

    private static final Logger log = LoggerFactory.getLogger(ToolCatalog.class);
    private static final int MAX_OUTPUT = 12_000;
    private static final int MAX_READ = 100_000;
    private static final int MAX_WRITE = 200_000;

    private static final List<ToolSpec> SPECS = List.of(
            tool(
                    "run_shell",
                    "Run a shell command in this container. The working directory is the agent workspace.",
                    Map.of("command", stringProp("Shell command to run")),
                    List.of("command")),
            tool(
                    "read_file",
                    "Read a UTF-8 text file from the agent workspace.",
                    Map.of("path", stringProp("Path relative to the workspace, or an absolute path inside it")),
                    List.of("path")),
            tool(
                    "write_file",
                    "Write a UTF-8 text file in the agent workspace. Creates parent directories.",
                    Map.of(
                            "path", stringProp("Path relative to the workspace, or an absolute path inside it"),
                            "content", stringProp("Full file contents")),
                    List.of("path", "content")),
            tool(
                    "message_agent",
                    "Delegate a subtask to another agent by name. Returns that agent's reply.",
                    Map.of(
                            "name", stringProp("Agent name"),
                            "message", stringProp("Task for that agent")),
                    List.of("name", "message")));

    private final Path workspace;
    private final String orchestratorUrl;
    private final String sender;
    private final int hops;
    private final Duration taskTimeout;
    private final Duration shellTimeout;
    private final HttpTransport http;
    private final ObjectMapper mapper;

    public ToolCatalog(
            Path workspace,
            String orchestratorUrl,
            String sender,
            int hops,
            Duration taskTimeout,
            Duration shellTimeout,
            HttpTransport http,
            ObjectMapper mapper) {
        this.workspace = workspace;
        this.orchestratorUrl = orchestratorUrl;
        this.sender = sender == null || sender.isBlank() ? "agent" : sender;
        this.hops = hops;
        this.taskTimeout = taskTimeout;
        this.shellTimeout = shellTimeout;
        this.http = http;
        this.mapper = mapper;
    }

    public List<ToolSpec> specs() {
        return SPECS;
    }

    public String execute(ToolCall call) {
        log.info("tool {} id={}", call.name(), call.id());
        try {
            Map<String, Object> arguments = call.arguments() == null ? Map.of() : call.arguments();
            if (arguments.containsKey("_invalid_json")) {
                return "error: tool arguments were not valid JSON";
            }
            return switch (call.name()) {
                case "run_shell" -> runShell(text(arguments, "command"));
                case "read_file" -> readFile(text(arguments, "path"));
                case "write_file" -> writeFile(text(arguments, "path"), text(arguments, "content"));
                case "message_agent" -> messageAgent(text(arguments, "name"), text(arguments, "message"));
                default -> "error: unknown tool " + call.name();
            };
        } catch (Exception exception) {
            return "error: " + exception.getMessage();
        }
    }

    private String runShell(String command) throws IOException, InterruptedException {
        if (command.isBlank()) {
            return "error: command is required";
        }
        if (command.length() > 4_000) {
            return "error: command is too long";
        }
        Files.createDirectories(workspace);
        ProcessBuilder builder = new ProcessBuilder("/bin/sh", "-c", command);
        builder.directory(workspace.toFile());
        builder.redirectErrorStream(true);
        Map<String, String> environment = builder.environment();
        environment.clear();
        environment.put("PATH", System.getenv().getOrDefault("PATH", "/usr/local/bin:/usr/bin:/bin"));
        environment.put("HOME", workspace.toString());
        environment.put("LANG", "C.UTF-8");
        environment.put("WORKSPACE", workspace.toString());
        String agentName = System.getenv("AGENT_NAME");
        if (agentName != null && !agentName.isBlank()) {
            environment.put("AGENT_NAME", agentName);
        }
        Process process = builder.start();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        Thread reader = Thread.ofVirtual().start(() -> {
            try {
                process.getInputStream().transferTo(captured);
            } catch (IOException ignored) {
                // The process is being torn down.
            }
        });
        boolean finished = process.waitFor(shellTimeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            reader.join(1_000);
            return "error: command timed out after " + shellTimeout.toSeconds() + "s\n"
                    + clip(captured.toString(StandardCharsets.UTF_8));
        }
        reader.join(1_000);
        String output = clip(captured.toString(StandardCharsets.UTF_8));
        if (process.exitValue() != 0) {
            return "exit " + process.exitValue() + "\n" + output;
        }
        return output.isBlank() ? "(no output)" : output;
    }

    private String readFile(String rawPath) throws IOException {
        Path path = resolve(rawPath);
        if (!Files.isRegularFile(path)) {
            return "error: file not found";
        }
        String contents = Files.readString(path);
        if (contents.length() > MAX_READ) {
            return "error: file is larger than " + MAX_READ + " characters";
        }
        return contents;
    }

    private String writeFile(String rawPath, String content) throws IOException {
        if (content.length() > MAX_WRITE) {
            return "error: content is larger than " + MAX_WRITE + " characters";
        }
        Path path = resolve(rawPath);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        return "wrote " + workspace.relativize(path);
    }

    private String messageAgent(String name, String message) throws IOException {
        if (message.isBlank()) {
            return "error: message is required";
        }
        try {
            Names.check(name);
        } catch (IllegalArgumentException exception) {
            return "error: " + exception.getMessage();
        }
        if (orchestratorUrl == null || orchestratorUrl.isBlank()) {
            return "error: orchestrator URL is not configured";
        }
        String url = trimSlash(orchestratorUrl) + "/agents/" + name + "/message";
        String payload = mapper.writeValueAsString(Map.of(
                "message", message,
                "hops", hops + 1,
                "from", sender));
        HttpTransport.Result result = http.exchange("POST", URI.create(url), payload, taskTimeout);
        if (result.status() >= 400) {
            return "error: orchestrator returned " + result.status() + ": " + clip(result.body());
        }
        JsonNode node = mapper.readTree(result.body());
        String reply = node.path("reply").asText("");
        return reply.isBlank() ? result.body() : reply;
    }

    private Path resolve(String raw) throws IOException {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("path is required");
        }
        Files.createDirectories(workspace);
        Path root = workspace.toRealPath();
        Path candidate = Path.of(raw);
        Path resolved = (candidate.isAbsolute() ? candidate : root.resolve(candidate)).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("path is outside the workspace");
        }
        if (Files.exists(resolved)) {
            Path real = resolved.toRealPath();
            if (!real.startsWith(root)) {
                throw new IllegalArgumentException("path is outside the workspace");
            }
            return real;
        }
        return resolved;
    }

    private static String text(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static String clip(String value) {
        if (value.length() <= MAX_OUTPUT) {
            return value;
        }
        return value.substring(0, MAX_OUTPUT) + "\n...[truncated]";
    }

    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static ToolSpec tool(
            String name, String description, Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        return new ToolSpec(name, description, schema);
    }

    private static Map<String, Object> stringProp(String description) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", "string");
        property.put("description", description);
        return property;
    }
}
