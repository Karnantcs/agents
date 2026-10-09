package dev.agentbox.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.ConflictException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.HostConfig;
import dev.agentbox.AgentboxProperties;
import dev.agentbox.agent.TaskResult;
import dev.agentbox.http.HttpTransport;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DockerAgentRuntime implements AgentRuntime {

    private static final Logger log = LoggerFactory.getLogger(DockerAgentRuntime.class);

    private final DockerClient docker;
    private final AgentboxProperties properties;
    private final HttpTransport http;
    private final ObjectMapper mapper;

    public DockerAgentRuntime(
            DockerClient docker, AgentboxProperties properties, HttpTransport http, ObjectMapper mapper) {
        this.docker = docker;
        this.properties = properties;
        this.http = http;
        this.mapper = mapper;
    }

    @Override
    public AgentRecord create(String name, String role) {
        Names.check(name);
        if (role == null || role.isBlank()) {
            throw new AgentErrors.StartFailed("role is required");
        }
        String container = Names.container(name);
        HostConfig host = HostConfig.newHostConfig()
                .withNetworkMode(properties.dockerNetwork())
                .withMemory(properties.memLimitBytes())
                .withMemorySwap(properties.memLimitBytes())
                .withNanoCPUs(properties.nanoCpus());
        CreateContainerResponse created;
        try {
            created = docker.createContainerCmd(properties.agentImage())
                    .withName(container)
                    .withEnv(environment(name, role))
                    .withLabels(Map.of("agentbox.managed", "true", "agentbox.name", name))
                    .withWorkingDir("/workspace")
                    .withHostConfig(host)
                    .exec();
        } catch (ConflictException exception) {
            throw new AgentErrors.Exists("agent " + name + " already exists");
        } catch (NotFoundException exception) {
            throw new AgentErrors.StartFailed("Agent image " + properties.agentImage()
                    + " was not found. Build it first: docker build -f Dockerfile --target agent -t "
                    + properties.agentImage() + " .");
        } catch (RuntimeException exception) {
            throw failure("create", exception);
        }

        try {
            docker.startContainerCmd(created.getId()).exec();
        } catch (RuntimeException exception) {
            removeQuietly(created.getId());
            throw failure("start", exception);
        }

        try {
            waitUntilReady(container);
        } catch (RuntimeException exception) {
            removeQuietly(created.getId());
            throw exception;
        }
        log.info("started agent {} container {}", name, container);
        return new AgentRecord(name, role, "running", container);
    }

    @Override
    public List<AgentRecord> list() {
        List<Container> containers;
        try {
            containers = docker.listContainersCmd()
                    .withShowAll(true)
                    .withLabelFilter(Map.of("agentbox.managed", "true"))
                    .exec();
        } catch (RuntimeException exception) {
            throw failure("list", exception);
        }
        List<AgentRecord> agents = new ArrayList<>();
        for (Container container : containers) {
            String name = container.getLabels() == null ? "" : container.getLabels().getOrDefault("agentbox.name", "");
            if (name.isBlank()) {
                continue;
            }
            InspectContainerResponse inspected = inspect(container.getId(), name);
            String status = container.getState() == null ? "unknown" : container.getState();
            agents.add(new AgentRecord(name, envValue(inspected.getConfig().getEnv(), "AGENT_ROLE"), status, Names.container(name)));
        }
        return agents;
    }

    @Override
    public TaskResult message(String name, String message, int hops) {
        Names.check(name);
        InspectContainerResponse inspected = inspect(Names.container(name), name);
        String status = "unknown";
        if (inspected.getState() != null && inspected.getState().getStatus() != null) {
            status = inspected.getState().getStatus();
        }
        if (!"running".equalsIgnoreCase(status)) {
            throw new AgentErrors.Unavailable("agent " + name + " is " + status);
        }
        String url = "http://" + Names.container(name) + ":" + properties.agentPort() + "/task";
        HttpTransport.Result response;
        try {
            String body = mapper.writeValueAsString(Map.of("message", message, "hops", hops));
            response = http.exchange("POST", URI.create(url), body, properties.taskTimeout());
        } catch (UncheckedIOException exception) {
            if (exception.getCause() instanceof HttpTimeoutException) {
                throw new AgentErrors.CallFailed(504, "agent " + name + " timed out");
            }
            throw new AgentErrors.CallFailed(502, "agent " + name + " is unreachable");
        } catch (Exception exception) {
            throw new AgentErrors.CallFailed(502, "agent " + name + " is unreachable");
        }
        if (response.status() >= 400) {
            throw new AgentErrors.CallFailed(response.status(), clip(response.body()));
        }
        try {
            JsonNode node = mapper.readTree(response.body());
            List<TaskResult.TraceStep> trace = mapper.convertValue(
                    node.path("trace"),
                    mapper.getTypeFactory().constructCollectionType(List.class, TaskResult.TraceStep.class));
            if (trace == null) {
                trace = List.of();
            }
            return new TaskResult(node.path("reply").asText(""), node.path("steps").asInt(0), trace);
        } catch (Exception exception) {
            throw new AgentErrors.CallFailed(502, "agent " + name + " returned an unreadable response");
        }
    }

    @Override
    public void remove(String name) {
        Names.check(name);
        InspectContainerResponse inspected = inspect(Names.container(name), name);
        removeQuietly(inspected.getId());
        log.info("removed agent {}", name);
    }

    private InspectContainerResponse inspect(String idOrName, String agentName) {
        try {
            return docker.inspectContainerCmd(idOrName).exec();
        } catch (NotFoundException exception) {
            throw new AgentErrors.NotFound("agent " + agentName + " was not found");
        } catch (RuntimeException exception) {
            throw failure("inspect", exception);
        }
    }

    private void waitUntilReady(String container) {
        URI health = URI.create("http://" + container + ":" + properties.agentPort() + "/health");
        long deadline = System.nanoTime() + properties.readyTimeout().toNanos();
        String last = "not checked";
        while (true) {
            try {
                HttpTransport.Result result = http.exchange("GET", health, null, Duration.ofSeconds(2));
                if (result.status() == 200) {
                    return;
                }
                last = "HTTP " + result.status();
            } catch (RuntimeException exception) {
                last = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            }
            if (System.nanoTime() >= deadline) {
                break;
            }
            try {
                Thread.sleep(300);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AgentErrors.StartFailed("interrupted while waiting for " + container);
            }
        }
        throw new AgentErrors.StartFailed("agent " + container + " did not become ready: " + last);
    }

    private void removeQuietly(String id) {
        try {
            docker.stopContainerCmd(id).withTimeout(5).exec();
        } catch (RuntimeException ignored) {
            // The container may already be stopped.
        }
        try {
            docker.removeContainerCmd(id).withForce(true).exec();
        } catch (RuntimeException exception) {
            log.warn("could not remove container {}: {}", id, exception.getMessage());
        }
    }

    private List<String> environment(String name, String role) {
        AgentboxProperties.Llm llm = properties.llm();
        return List.of(
                "AGENTBOX_MODE=agent",
                "SERVER_PORT=" + properties.agentPort(),
                "AGENT_NAME=" + name,
                "AGENT_ROLE=" + role,
                "ORCHESTRATOR_URL=" + properties.orchestratorUrl(),
                "WORKSPACE=/workspace",
                "LLM_PROVIDER=" + llm.provider(),
                "ANTHROPIC_API_KEY=" + nullToEmpty(llm.anthropicApiKey()),
                "ANTHROPIC_MODEL=" + llm.anthropicModel(),
                "OPENAI_API_KEY=" + nullToEmpty(llm.openaiApiKey()),
                "OPENAI_BASE_URL=" + llm.openaiBaseUrl(),
                "OPENAI_MODEL=" + llm.openaiModel(),
                "LLM_MAX_TOKENS=" + llm.maxTokens(),
                "AGENT_MAX_STEPS=" + properties.maxSteps(),
                "TASK_TIMEOUT=" + properties.taskTimeout());
    }

    static String envValue(String[] environment, String key) {
        if (environment == null) {
            return "";
        }
        String prefix = key + "=";
        for (String entry : environment) {
            if (entry != null && entry.startsWith(prefix)) {
                return entry.substring(prefix.length());
            }
        }
        return "";
    }

    private static RuntimeException failure(String action, RuntimeException exception) {
        if (exception instanceof AgentErrors.NotFound
                || exception instanceof AgentErrors.Exists
                || exception instanceof AgentErrors.StartFailed
                || exception instanceof AgentErrors.Unavailable
                || exception instanceof AgentErrors.CallFailed) {
            return exception;
        }
        String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        if (message.contains("SocketException") || message.contains("docker.sock") || message.contains("Connection refused")) {
            message = "Cannot reach the Docker engine. Start Docker and mount /var/run/docker.sock for the orchestrator. ("
                    + message + ")";
        }
        return new AgentErrors.StartFailed(action + " failed: " + message);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String clip(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
