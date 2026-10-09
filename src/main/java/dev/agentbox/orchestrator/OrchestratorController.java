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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@ConditionalOnProperty(name = "agentbox.mode", havingValue = "orchestrator", matchIfMissing = true)
public class OrchestratorController {

    private final AgentRuntime runtime;
    private final AgentboxProperties properties;
    private final String indexHtml;

    public OrchestratorController(AgentRuntime runtime, AgentboxProperties properties) throws IOException {
        this.runtime = runtime;
        this.properties = properties;
        this.indexHtml = new ClassPathResource("static/index.html").getContentAsString(StandardCharsets.UTF_8);
    }

    public record CreateAgentRequest(
            @NotBlank @Pattern(regexp = Names.PATTERN, message = "use a lowercase letter, then lowercase letters, digits, or hyphens (max 32)")
            String name,
            @NotBlank @Size(max = 8_000) String role) {}

    public record MessageRequest(@NotBlank @Size(max = 32_000) String message, Integer hops) {}

    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    public String home() {
        return indexHtml;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    @GetMapping("/agents")
    public List<AgentRecord> list() {
        try {
            return runtime.list();
        } catch (AgentErrors.StartFailed exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage());
        }
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
        try {
            return runtime.message(name, request.message().trim(), hops);
        } catch (AgentErrors.NotFound exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage());
        } catch (AgentErrors.Unavailable exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage());
        } catch (AgentErrors.CallFailed exception) {
            HttpStatus status = HttpStatus.resolve(exception.status());
            throw new ResponseStatusException(status == null ? HttpStatus.BAD_GATEWAY : status, exception.getMessage());
        } catch (AgentErrors.StartFailed exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, exception.getMessage());
        }
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
