package dev.agentbox.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.command.StartContainerCmd;
import com.github.dockerjava.api.command.StopContainerCmd;
import com.github.dockerjava.api.exception.ConflictException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerConfig;
import com.github.dockerjava.api.model.HostConfig;
import dev.agentbox.TestAgents;
import dev.agentbox.agent.TaskResult;
import dev.agentbox.http.HttpTransport;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DockerAgentRuntimeTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void createPassesRoleAndKeyIntoTheContainerWithoutMountingTheSocket() {
        DockerClient docker = mock(DockerClient.class);
        CreateContainerCmd create = mock(CreateContainerCmd.class, RETURNS_SELF);
        CreateContainerResponse response = new CreateContainerResponse();
        response.setId("cid");
        when(create.exec()).thenReturn(response);
        when(docker.createContainerCmd("agentbox-agent:latest")).thenReturn(create);
        StartContainerCmd start = mock(StartContainerCmd.class);
        when(docker.startContainerCmd("cid")).thenReturn(start);
        HttpTransport http = (method, uri, body, timeout) -> new HttpTransport.Result(200, "{\"status\":\"ok\"}");

        DockerAgentRuntime runtime = new DockerAgentRuntime(docker, TestAgents.properties("anthropic", "secret-key"), http, mapper);
        AgentRecord created = runtime.create("lead", "coordinate workers");

        assertThat(created.name()).isEqualTo("lead");
        assertThat(created.status()).isEqualTo("running");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> env = ArgumentCaptor.forClass(List.class);
        verify(create).withEnv(env.capture());
        assertThat(env.getValue()).contains(
                "AGENT_NAME=lead",
                "AGENT_ROLE=coordinate workers",
                "ANTHROPIC_API_KEY=secret-key",
                "LLM_PROVIDER=anthropic",
                "ORCHESTRATOR_URL=http://orchestrator:8091");
        assertThat(env.getValue()).noneMatch(entry -> entry.startsWith("DOCKER_HOST"));
        ArgumentCaptor<HostConfig> host = ArgumentCaptor.forClass(HostConfig.class);
        verify(create).withHostConfig(host.capture());
        assertThat(host.getValue().getNetworkMode()).isEqualTo("agentbox_default");
        assertThat(host.getValue().getMemory()).isEqualTo(536_870_912L);
        assertThat(host.getValue().getBinds()).isEmpty();
        verify(create).withLabels(Map.of("agentbox.managed", "true", "agentbox.name", "lead"));
        verify(start).exec();
    }

    @Test
    void createRollsBackWhenTheAgentNeverBecomesReady() {
        DockerClient docker = mock(DockerClient.class);
        CreateContainerCmd create = mock(CreateContainerCmd.class, RETURNS_SELF);
        CreateContainerResponse response = new CreateContainerResponse();
        response.setId("cid");
        when(create.exec()).thenReturn(response);
        when(docker.createContainerCmd(anyString())).thenReturn(create);
        when(docker.startContainerCmd("cid")).thenReturn(mock(StartContainerCmd.class));
        StopContainerCmd stop = mock(StopContainerCmd.class, RETURNS_SELF);
        RemoveContainerCmd remove = mock(RemoveContainerCmd.class, RETURNS_SELF);
        when(docker.stopContainerCmd("cid")).thenReturn(stop);
        when(docker.removeContainerCmd("cid")).thenReturn(remove);
        HttpTransport http = (method, uri, body, timeout) -> {
            throw new java.io.UncheckedIOException(new java.io.IOException("connection refused"));
        };

        DockerAgentRuntime runtime = new DockerAgentRuntime(docker, TestAgents.properties("anthropic", "secret-key"), http, mapper);
        assertThatThrownBy(() -> runtime.create("lead", "role"))
                .isInstanceOf(AgentErrors.StartFailed.class)
                .hasMessageContaining("did not become ready");
        verify(remove).exec();
    }

    @Test
    void duplicateNameIsAConflict() {
        DockerClient docker = mock(DockerClient.class);
        CreateContainerCmd create = mock(CreateContainerCmd.class, RETURNS_SELF);
        when(create.exec()).thenThrow(new ConflictException("name in use"));
        when(docker.createContainerCmd(anyString())).thenReturn(create);
        DockerAgentRuntime runtime = new DockerAgentRuntime(
                docker, TestAgents.properties("anthropic", "k"), (m, u, b, t) -> new HttpTransport.Result(200, ""), mapper);
        assertThatThrownBy(() -> runtime.create("lead", "role")).isInstanceOf(AgentErrors.Exists.class);
    }

    @Test
    void listReadsRoleFromTheContainerEnvAndMessagePostsTheTask() throws Exception {
        DockerClient docker = mock(DockerClient.class);
        Container container = mock(Container.class);
        when(container.getId()).thenReturn("cid");
        when(container.getState()).thenReturn("running");
        when(container.getLabels()).thenReturn(Map.of("agentbox.managed", "true", "agentbox.name", "worker"));
        ListContainersCmd list = mock(ListContainersCmd.class, RETURNS_SELF);
        when(list.exec()).thenReturn(List.of(container));
        when(docker.listContainersCmd()).thenReturn(list);
        stubInspect(docker, "cid", "worker", "running", "hands on");
        stubInspect(docker, "agentbox-worker", "worker", "running", "hands on");

        HttpTransport http = (method, uri, body, timeout) -> {
            assertThat(method).isEqualTo("POST");
            assertThat(uri).isEqualTo(URI.create("http://agentbox-worker:8092/task"));
            assertThat(body).contains("\"hops\":1");
            return new HttpTransport.Result(200, "{\"reply\":\"filed\",\"steps\":3,\"trace\":[{\"tool\":\"write_file\",\"input\":{\"path\":\"a.txt\"},\"output\":\"wrote a.txt\"}]}");
        };
        DockerAgentRuntime runtime = new DockerAgentRuntime(docker, TestAgents.properties("anthropic", "secret-key"), http, mapper);

        assertThat(runtime.list()).singleElement().satisfies(agent -> {
            assertThat(agent.name()).isEqualTo("worker");
            assertThat(agent.role()).isEqualTo("hands on");
            assertThat(agent.status()).isEqualTo("running");
            assertThat(agent.role()).doesNotContain("secret-key");
        });
        TaskResult result = runtime.message("worker", "write a file", 1);
        assertThat(result.reply()).isEqualTo("filed");
        assertThat(result.steps()).isEqualTo(3);
        assertThat(result.trace()).singleElement().satisfies(step -> assertThat(step.tool()).isEqualTo("write_file"));
    }

    @Test
    void removeMissingAgent() {
        DockerClient docker = mock(DockerClient.class);
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        when(docker.inspectContainerCmd("agentbox-ghost")).thenReturn(inspect);
        when(inspect.exec()).thenThrow(new NotFoundException("missing"));
        DockerAgentRuntime runtime = new DockerAgentRuntime(
                docker, TestAgents.properties("anthropic", "k"), (m, u, b, t) -> new HttpTransport.Result(200, ""), mapper);
        assertThatThrownBy(() -> runtime.remove("ghost")).isInstanceOf(AgentErrors.NotFound.class);
    }

    @Test
    void envValueSplitsOnTheFirstEquals() {
        assertThat(DockerAgentRuntime.envValue(new String[] {"AGENT_ROLE=a=b", "AGENT_NAME=lead"}, "AGENT_ROLE"))
                .isEqualTo("a=b");
    }

    private static void stubInspect(DockerClient docker, String id, String name, String status, String role) {
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        when(docker.inspectContainerCmd(id)).thenReturn(inspect);
        InspectContainerResponse response = mock(InspectContainerResponse.class);
        when(inspect.exec()).thenReturn(response);
        when(response.getId()).thenReturn("cid");
        when(response.getName()).thenReturn("/agentbox-" + name);
        ContainerConfig config = mock(ContainerConfig.class);
        when(response.getConfig()).thenReturn(config);
        when(config.getEnv()).thenReturn(new String[] {
            "AGENT_ROLE=" + role,
            "AGENT_NAME=" + name,
            "ANTHROPIC_API_KEY=secret-key"
        });
        InspectContainerResponse.ContainerState state = mock(InspectContainerResponse.ContainerState.class);
        when(response.getState()).thenReturn(state);
        when(state.getStatus()).thenReturn(status);
    }
}
