package dev.agentbox.orchestrator;

import dev.agentbox.AgentboxProperties;
import dev.agentbox.agent.TaskResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@ConditionalOnProperty(name = "agentbox.mode", havingValue = "orchestrator", matchIfMissing = true)
public class OrchestratorController {

    private final AgentRuntime runtime;
    private final AgentboxProperties properties;
    private final ActivityLog activityLog;
    private final String indexHtml;

    public OrchestratorController(AgentRuntime runtime, AgentboxProperties properties, ActivityLog activityLog)
            throws IOException {
        this.runtime = runtime;
        this.properties = properties;
        this.activityLog = activityLog;
        this.indexHtml = new ClassPathResource("static/index.html").getContentAsString(StandardCharsets.UTF_8);
    }

    public record CreateAgentRequest(
            @NotBlank @Pattern(regexp = Names.PATTERN, message = "use a lowercase letter, then lowercase letters, digits, or hyphens (max 32)")
            String name,
            @NotBlank @Size(max = 8_000) String role) {}

    public record MessageRequest(
            @NotBlank @Size(max = 32_000) String message,
            Integer hops,
            @Size(max = 32) String from,
            Long parent) {}

    public record AgentResponse(
            String name,
            String role,
            String status,
            String container,
            String availability,
            String currentTask,
            String currentFrom) {}

    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    public String home() {
        return indexHtml;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    @GetMapping("/agents")
    public List<AgentResponse> list() {
        try {
            return runtime.list().stream().map(this::withLiveState).toList();
        } catch (AgentErrors.StartFailed exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage());
        }
    }

    @GetMapping("/activity")
    public List<ActivityLog.View> activity(@RequestParam(name = "agent", required = false) String agent) {
        return activityLog.list(agent);
    }

    @GetMapping("/agents/{name}/activity")
    public List<ActivityLog.View> agentActivity(@PathVariable String name) {
        try {
            Names.check(name);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
        return activityLog.list(name);
    }

    @PostMapping("/agents")
    public ResponseEntity<AgentRecord> create(@Valid @RequestBody CreateAgentRequest request) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(runtime.create(request.name(), request.role().trim()));
        } catch (AgentErrors.Exists exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage());
        } catch (AgentErrors.StartFailed exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    @PostMapping("/agents/{name}/message")
    public TaskResult message(@PathVariable String name, @Valid @RequestBody MessageRequest request) {
        int hops = request.hops() == null ? 0 : request.hops();
        if (hops < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "hops must be zero or greater");
        }
        if (hops >= properties.maxHops()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "delegation limit reached (" + properties.maxHops() + " hops)");
        }
        try {
            Names.check(name);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
        String sender = request.from() == null || request.from().isBlank() ? "user" : request.from().trim();
        ActivityLog.Entry entry = activityLog.open(sender, name, request.message().trim(), request.parent());
        try {
            activityLog.markWorking(entry.id());
            TaskResult result = runtime.message(name, request.message().trim(), hops);
            activityLog.complete(entry.id(), result.reply());
            recordTools(name, entry.id(), result);
            return result;
        } catch (RuntimeException exception) {
            activityLog.fail(entry.id(), exception.getMessage());
            throw messageFailure(exception);
        }
    }

    private AgentResponse withLiveState(AgentRecord agent) {
        ActivityLog.Live live = activityLog.live(agent.name());
        return new AgentResponse(
                agent.name(),
                agent.role(),
                agent.status(),
                agent.container(),
                live.availability(),
                live.task(),
                live.from());
    }

    private void recordTools(String agent, long parentId, TaskResult result) {
        if (result.trace() == null) {
            return;
        }
        for (TaskResult.TraceStep step : result.trace()) {
            if (step == null || step.tool() == null || "message_agent".equals(step.tool())) {
                continue;
            }
            activityLog.recordTool(agent, step.tool(), toolInput(step.input()), step.output(), parentId);
        }
    }

    private static String toolInput(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, Object> field : input.entrySet()) {
            if (!builder.isEmpty()) {
                builder.append('\n');
            }
            builder.append(field.getKey()).append(": ").append(field.getValue());
        }
        return builder.toString();
    }

    private static RuntimeException messageFailure(RuntimeException exception) {
        if (exception instanceof AgentErrors.NotFound notFound) {
            return new ResponseStatusException(HttpStatus.NOT_FOUND, notFound.getMessage());
        }
        if (exception instanceof AgentErrors.Unavailable unavailable) {
            return new ResponseStatusException(HttpStatus.CONFLICT, unavailable.getMessage());
        }
        if (exception instanceof AgentErrors.CallFailed failed) {
            HttpStatus status = HttpStatus.resolve(failed.status());
            return new ResponseStatusException(status == null ? HttpStatus.BAD_GATEWAY : status, failed.getMessage());
        }
        if (exception instanceof AgentErrors.StartFailed failed) {
            return new ResponseStatusException(HttpStatus.BAD_GATEWAY, failed.getMessage());
        }
        return exception;
    }

    @DeleteMapping("/agents/{name}")
    public ResponseEntity<Void> remove(@PathVariable String name) {
        try {
            runtime.remove(name);
        } catch (AgentErrors.NotFound exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        } catch (AgentErrors.StartFailed exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, exception.getMessage());
        }
        return ResponseEntity.noContent().build();
    }
}
