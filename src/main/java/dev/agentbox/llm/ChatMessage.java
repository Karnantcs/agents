package dev.agentbox.llm;

public sealed interface ChatMessage permits ChatMessage.UserText, ChatMessage.AssistantTurn, ChatMessage.ToolResult {

    record UserText(String text) implements ChatMessage {}

    record AssistantTurn(LlmTurn turn) implements ChatMessage {}

    record ToolResult(String toolCallId, String output) implements ChatMessage {}
}
