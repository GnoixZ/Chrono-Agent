package ai.chrono.backend.agent;

import ai.chrono.backend.demo.DemoAgentMessageRequest;
import ai.chrono.backend.demo.DemoAgentMessageResponse;
import ai.chrono.backend.demo.DemoPipelineService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent")
public class AgentController {
    private final AgentService agentService;
    private final DemoPipelineService demoPipelineService;

    public AgentController(AgentService agentService, DemoPipelineService demoPipelineService) {
        this.agentService = agentService;
        this.demoPipelineService = demoPipelineService;
    }

    @PostMapping("/check-in")
    ResponseEntity<AgentMessageResponse> checkIn(@Valid @RequestBody AgentMessageRequest request) {
        return ResponseEntity.ok(agentService.reply(request));
    }

    @PostMapping("/messages")
    ResponseEntity<DemoAgentMessageResponse> sendMessage(@Valid @RequestBody DemoAgentMessageRequest request) {
        return ResponseEntity.ok(demoPipelineService.sendAgentMessage(request));
    }
}
