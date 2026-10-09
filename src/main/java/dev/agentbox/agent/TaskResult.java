package dev.agentbox.agent;

import java.util.List;
import java.util.Map;

public record TaskResult(String reply, int steps, List<TraceStep> trace) {

    public record TraceStep(String tool, Map<String, Object> input, String output) {}
}
