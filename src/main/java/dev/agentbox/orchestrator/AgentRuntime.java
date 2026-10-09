package dev.agentbox.orchestrator;

import dev.agentbox.agent.TaskResult;
import java.util.List;

public interface AgentRuntime {

    AgentRecord create(String name, String role);

    List<AgentRecord> list();

    TaskResult message(String name, String message, int hops);

    void remove(String name);
}
