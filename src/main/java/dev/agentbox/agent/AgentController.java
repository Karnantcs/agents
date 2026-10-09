package dev.agentbox.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentbox.AgentboxProperties;
import dev.agentbox.http.JdkHttpTransport;
import dev.agentbox.llm.LlmClient;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@ConditionalOnProperty(name = "agentbox.mode", havingValue = "agent")
public class AgentController {

    private final LlmClient llm;
    private final AgentboxProperties properties;
    private final ObjectMapper mapper;
    private final JdkHttpTransport http = new JdkHttpTransport();
    private final ReentrantLock lock = new ReentrantLock();

    public AgentController(LlmClient llm, AgentboxProperties properties, ObjectMapper mapper) {
        this.llm = llm;
        this.properties = properties;
        this.mapper = mapper;
    }

    public record TaskRequest(
            @NotBlank @Size(max = 32_000) String message,
            Integer hops) {}

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok", "agent", properties.agentName());
    }

    @PostMapping("/task")
    public TaskResult task(@Valid @RequestBody TaskRequest request) {
        if (!lock.tryLock()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "agent is busy with another task");
        }
        try {
            int hops = request.hops() == null ? 0 : request.hops();
            ToolCatalog tools = new ToolCatalog(
                    Path.of(properties.workspace()),
                    properties.orchestratorUrl(),
                    hops,
                    properties.taskTimeout(),
                    java.time.Duration.ofSeconds(20),
                    http,
                    mapper);
            return new AgentLoop(llm, properties.maxSteps())
                    .run(Prompts.system(properties.role()), request.message(), tools);
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            String message = exception.getMessage() == null ? "agent failed" : exception.getMessage();
            if (message.length() > 500) {
                message = message.substring(0, 500);
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, message);
        } finally {
            lock.unlock();
        }
    }
}
