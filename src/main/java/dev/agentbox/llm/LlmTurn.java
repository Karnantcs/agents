package dev.agentbox.llm;

import java.util.List;

public record LlmTurn(String text, List<ToolCall> calls, Object replay) {

    public LlmTurn {
        calls = calls == null ? List.of() : List.copyOf(calls);
    }

    public boolean hasCalls() {
        return !calls.isEmpty();
    }
}
