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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = OrchestratorController.class, properties = "agentbox.mode=orchestrator")
class OrchestratorControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AgentRuntime runtime;

    @Test
    void homePageOffersCreateAndChat() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Agentbox")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Start agent")));
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
                .andExpect(jsonPath("$[0].status").value("running"));

        mvc.perform(post("/agents/worker/message")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"say hi\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("hello"));

        mvc.perform(delete("/agents/worker")).andExpect(status().isNoContent());
        verify(runtime).remove("worker");
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
