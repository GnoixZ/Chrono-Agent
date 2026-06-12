package ai.chrono.backend.conversation;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {
    private final ConversationCorrectionService correctionService;

    public ConversationController(ConversationCorrectionService correctionService) {
        this.correctionService = correctionService;
    }

    @PostMapping("/merge")
    ResponseEntity<Map<String, Object>> merge(@Valid @RequestBody ConversationMergeRequest request) {
        return ResponseEntity.ok(correctionService.merge(request));
    }

    @PostMapping("/{conversationMemoryId}/split")
    ResponseEntity<Map<String, Object>> split(
            @PathVariable UUID conversationMemoryId,
            @Valid @RequestBody ConversationSplitRequest request
    ) {
        return ResponseEntity.ok(correctionService.split(conversationMemoryId, request));
    }

    @PostMapping("/{conversationMemoryId}/reprocess")
    ResponseEntity<Map<String, Object>> reprocess(
            @PathVariable UUID conversationMemoryId,
            @Valid @RequestBody ConversationReprocessRequest request
    ) {
        return ResponseEntity.ok(correctionService.reprocess(conversationMemoryId, request));
    }
}
