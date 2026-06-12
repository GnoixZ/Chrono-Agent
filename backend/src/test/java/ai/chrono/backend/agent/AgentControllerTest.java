package ai.chrono.backend.agent;

import ai.chrono.backend.demo.DemoAgentMessageResponse;
import ai.chrono.backend.demo.DemoPipelineService;
import ai.chrono.backend.memory.MemoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AgentController.class)
@Import({AgentService.class, MemoryService.class})
class AgentControllerTest {
    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    DemoPipelineService demoPipelineService;

    @Test
    void checkInReturnsSupportiveResponse() throws Exception {
        String json = """
                {
                  "userId": "user-1",
                  "content": "我今天感觉很累"
                }
                """;

        mockMvc.perform(post("/api/agent/check-in").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.safetyLevel").value("normal"))
                .andExpect(jsonPath("$.content").isNotEmpty());
    }

    @Test
    void messagesUsesPersistentAgentPipeline() throws Exception {
        when(demoPipelineService.sendAgentMessage(any())).thenReturn(new DemoAgentMessageResponse(
                "session-1",
                "user-message-1",
                "assistant-message-1",
                "run-1",
                "正式 Agent 回复",
                "normal",
                List.of(),
                List.of()
        ));

        String json = """
                {
                  "userId": "user-1",
                  "conversationSessionId": "session-1",
                  "content": "最近有什么线索？"
                }
                """;

        mockMvc.perform(post("/api/agent/messages").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationSessionId").value("session-1"))
                .andExpect(jsonPath("$.runId").value("run-1"))
                .andExpect(jsonPath("$.content").value("正式 Agent 回复"));
    }
}
