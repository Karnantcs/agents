package dev.agentbox.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import dev.agentbox.AgentboxProperties;
import dev.agentbox.http.HttpTransport;
import dev.agentbox.http.JdkHttpTransport;
import dev.agentbox.llm.LlmClient;
import dev.agentbox.llm.LlmClients;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OrchestratorConfig {

    @Bean
    @ConditionalOnProperty(name = "agentbox.mode", havingValue = "orchestrator", matchIfMissing = true)
    DockerClient dockerClient() {
        DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        DockerHttpClient http = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .connectionTimeout(Duration.ofSeconds(5))
                .responseTimeout(Duration.ofSeconds(30))
                .build();
        return DockerClientImpl.getInstance(config, http);
    }

    @Bean
    @ConditionalOnProperty(name = "agentbox.mode", havingValue = "orchestrator", matchIfMissing = true)
    AgentRuntime agentRuntime(DockerClient docker, AgentboxProperties properties, ObjectMapper mapper) {
        return new DockerAgentRuntime(docker, properties, new JdkHttpTransport(), mapper);
    }

    @Bean
    @ConditionalOnProperty(name = "agentbox.mode", havingValue = "agent")
    LlmClient llmClient(AgentboxProperties properties, ObjectMapper mapper) {
        HttpTransport http = new JdkHttpTransport();
        return LlmClients.create(properties, mapper, http);
    }
}
