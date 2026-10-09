package dev.agentbox.agent;

import dev.agentbox.llm.ChatMessage;
import dev.agentbox.llm.LlmClient;
import dev.agentbox.llm.LlmTurn;
import dev.agentbox.llm.ToolCall;
import java.util.ArrayList;
import java.util.List;

public final class AgentLoop {

    private final LlmClient llm;
    private final int maxSteps;

    public AgentLoop(LlmClient llm, int maxSteps) {
        this.llm = llm;
        this.maxSteps = maxSteps;
    }

    public TaskResult run(String system, String task, ToolCatalog tools) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage.UserText(task));
        List<TaskResult.TraceStep> trace = new ArrayList<>();
        String lastText = null;
        for (int step = 1; step <= maxSteps; step++) {
            LlmTurn turn = llm.complete(system, messages, tools.specs());
            messages.add(new ChatMessage.AssistantTurn(turn));
            if (turn.text() != null && !turn.text().isBlank()) {
                lastText = turn.text();
            }
            if (!turn.hasCalls()) {
                String reply = turn.text() == null || turn.text().isBlank() ? "(empty reply)" : turn.text();
                return new TaskResult(reply, step, List.copyOf(trace));
            }
            for (ToolCall call : turn.calls()) {
                String output = tools.execute(call);
                trace.add(new TaskResult.TraceStep(call.name(), call.arguments(), output));
                messages.add(new ChatMessage.ToolResult(call.id(), output));
            }
        }
        String reply = lastText == null
                ? "Stopped after " + maxSteps + " steps without a final answer."
                : "Stopped after " + maxSteps + " steps. Last note: " + lastText;
        return new TaskResult(reply, maxSteps, List.copyOf(trace));
    }
}
