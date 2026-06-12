package ai.chrono.backend.demo;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/demo")
public class DemoController {
    private final DemoPipelineService demoPipelineService;

    public DemoController(DemoPipelineService demoPipelineService) {
        this.demoPipelineService = demoPipelineService;
    }

    @PostMapping("/audio")
    ResponseEntity<DemoAudioResult> uploadAudio(@RequestParam String userId, @RequestParam MultipartFile file) throws IOException {
        return ResponseEntity.ok(demoPipelineService.processAudio(userId, file.getOriginalFilename(), file.getBytes(), "upload", null));
    }

    @GetMapping("/state")
    ResponseEntity<DemoStateResponse> state(@RequestParam String userId) {
        return ResponseEntity.ok(demoPipelineService.getState(userId));
    }

    @PostMapping("/health")
    ResponseEntity<Map<String, Object>> createHealthEvent(@Valid @RequestBody DemoHealthRequest request) {
        return ResponseEntity.ok(demoPipelineService.createHealthEvent(request));
    }

    @PatchMapping("/speakers/{speakerClusterId}/label")
    ResponseEntity<Map<String, Object>> labelSpeaker(@PathVariable UUID speakerClusterId, @Valid @RequestBody SpeakerLabelRequest request) {
        return ResponseEntity.ok(demoPipelineService.labelSpeaker(request.userId(), speakerClusterId, request.displayName()));
    }

    @PostMapping("/memory-candidates/{candidateId}/accept")
    ResponseEntity<Map<String, Object>> acceptMemoryCandidate(@PathVariable UUID candidateId) {
        return ResponseEntity.ok(demoPipelineService.acceptMemoryCandidate(candidateId));
    }

    @PostMapping("/memory-candidates/{candidateId}/reject")
    ResponseEntity<Map<String, Object>> rejectMemoryCandidate(@PathVariable UUID candidateId) {
        return ResponseEntity.ok(demoPipelineService.rejectMemoryCandidate(candidateId));
    }

}
