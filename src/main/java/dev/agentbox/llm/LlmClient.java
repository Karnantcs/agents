package dev.agentbox.llm;

import java.util.List;

public interface LlmClient {

    LlmTurn complete(String system, List<ChatMessage> messages, List<ToolSpec> tools);
}
