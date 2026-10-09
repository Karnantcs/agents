package dev.agentbox.orchestrator;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.agentbox.agent.TaskResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = OrchestratorController.class, properties = "agentbox.mode=orchestrator")
@Import(ActivityLog.class)
class OrchestratorControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ActivityLog activityLog;

    @MockitoBean
    private AgentRuntime runtime;

    @BeforeEach
    void resetActivity() {
        activityLog.clear();
    }

    @Test
    void homePageOffersCreateAndChat() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Agentbox")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Start agent")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Activity")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("#/agents/")));
    }

    @Test
    void createListMessageAndRemove() throws Exception {
        when(runtime.create("worker", "hands on")).thenReturn(new AgentRecord("worker", "hands on", "running", "agentbox-worker"));
        when(runtime.list()).thenReturn(List.of(new AgentRecord("worker", "hands on", "running", "agentbox-worker")));
        when(runtime.message(eq("worker"), eq("say hi"), eq(0)))
                .thenReturn(new TaskResult("hello", 1, List.of()));

        mvc.perform(post("/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"worker\",\"role\":\"hands on\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.container").value("agentbox-worker"))
                .andExpect(jsonPath("$.role").value("hands on"));

        mvc.perform(get("/agents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("worker"))
                .andExpect(jsonPath("$[0].status").value("running"))
                .andExpect(jsonPath("$[0].availability").value("idle"));

        mvc.perform(post("/agents/worker/message")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"say hi\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("hello"));

        mvc.perform(delete("/agents/worker")).andExpect(status().isNoContent());
        verify(runtime).remove("worker");

        mvc.perform(get("/activity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].from").value("user"))
                .andExpect(jsonPath("$[0].to").value("worker"))
                .andExpect(jsonPath("$[0].message").value("say hi"))
                .andExpect(jsonPath("$[0].status").value("done"))
                .andExpect(jsonPath("$[0].reply").value("hello"))
                .andExpect(jsonPath("$[0].durationMillis").isNumber());

        mvc.perform(get("/activity").param("agent", "other"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void agentActivityListsTheTaskAndItsToolCalls() throws Exception {
        when(runtime.message(eq("worker"), eq("write it"), eq(0))).thenReturn(new TaskResult(
                "wrote it",
                2,
                List.of(
                        new TaskResult.TraceStep("write_file", Map.of("path", "hello.txt"), "wrote hello.txt"),
                        new TaskResult.TraceStep("message_agent", Map.of("name", "lead"), "ok"))));

        mvc.perform(post("/agents/worker/message")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"write it\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/agents/worker/activity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].kind").value("tool"))
                .andExpect(jsonPath("$[0].from").value("worker"))
                .andExpect(jsonPath("$[0].to").value("write_file"))
                .andExpect(jsonPath("$[0].message").value(org.hamcrest.Matchers.containsString("hello.txt")))
                .andExpect(jsonPath("$[0].reply").value("wrote hello.txt"))
                .andExpect(jsonPath("$[0].parentId").value(1))
                .andExpect(jsonPath("$[1].kind").value("message"))
                .andExpect(jsonPath("$[1].from").value("user"))
                .andExpect(jsonPath("$[1].to").value("worker"))
                .andExpect(jsonPath("$[1].status").value("done"))
                .andExpect(jsonPath("$[1].reply").value("wrote it"));

        mvc.perform(get("/agents/NOPE/activity")).andExpect(status().isBadRequest());
    }

    @Test
    void rejectsABadNameAndADelegationLoop() throws Exception {
        mvc.perform(post("/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Worker\",\"role\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").exists());

        mvc.perform(post("/agents/lead/message")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"again\",\"hops\":4}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("delegation limit")));
        verify(runtime, never()).message(anyString(), anyString(), anyInt());
    }

    @Test
    void missingAgentIs404() throws Exception {
        when(runtime.message("missing", "hi", 0)).thenThrow(new AgentErrors.NotFound("agent missing was not found"));
        mvc.perform(post("/agents/missing/message")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hi\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("agent missing was not found"));
    }
}
