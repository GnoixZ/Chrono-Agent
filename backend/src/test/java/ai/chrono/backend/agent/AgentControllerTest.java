package ai.chrono.backend.agent;

import ai.chrono.backend.memory.MemoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AgentController.class)
@Import({AgentService.class, MemoryService.class})
class AgentControllerTest {
    @Autowired
    MockMvc mockMvc;

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
}
