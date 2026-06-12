package ai.chrono.backend.health;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/health/events")
public class HealthController {
    private final HealthService healthService;

    public HealthController(HealthService healthService) {
        this.healthService = healthService;
    }

    @PostMapping
    ResponseEntity<HealthEventResponse> create(@Valid @RequestBody HealthEventRequest request) {
        if (!HealthEventTypes.isAllowed(request.eventType())) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(healthService.create(request));
    }

    @GetMapping
    ResponseEntity<List<HealthEventResponse>> list(
            @RequestParam String userId,
            @RequestParam(required = false) Instant start,
            @RequestParam(required = false) Instant end,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) Integer limit
    ) {
        if (eventType != null && !eventType.isBlank() && !HealthEventTypes.isAllowed(eventType)) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(healthService.list(userId, start, end, eventType, limit));
    }
}
