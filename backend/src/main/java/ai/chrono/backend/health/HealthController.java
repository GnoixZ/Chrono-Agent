package ai.chrono.backend.health;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/health/events")
public class HealthController {
    @PostMapping
    ResponseEntity<HealthEventResponse> create(@Valid @RequestBody HealthEventRequest request) {
        if (!HealthEventTypes.isAllowed(request.eventType())) {
            return ResponseEntity.badRequest().build();
        }
        String displayValue = request.valueText() != null ? request.valueText() : String.valueOf(request.valueNumeric());
        return ResponseEntity.ok(new HealthEventResponse(
                UUID.randomUUID().toString(),
                request.userId(),
                request.eventType(),
                request.measuredAt().toString(),
                displayValue
        ));
    }
}
