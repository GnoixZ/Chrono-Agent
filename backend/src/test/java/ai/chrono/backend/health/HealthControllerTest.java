package ai.chrono.backend.health;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HealthControllerTest {
    private final HealthService healthService = mock(HealthService.class);
    private final HealthController controller = new HealthController(healthService);

    @Test
    void createPersistsHealthEventThroughService() {
        HealthEventRequest request = new HealthEventRequest(
                "user-1",
                "heart_rate",
                Instant.parse("2026-06-12T09:00:00Z"),
                88.0,
                null,
                "bpm",
                "manual"
        );
        when(healthService.create(any())).thenReturn(new HealthEventResponse(
                "event-1",
                "user-1",
                "heart_rate",
                "2026-06-12T09:00:00Z",
                88.0,
                null,
                "bpm",
                "manual",
                "88.0bpm",
                "2026-06-12T09:00:05Z"
        ));

        ResponseEntity<HealthEventResponse> response = controller.create(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().id()).isEqualTo("event-1");
        assertThat(response.getBody().displayValue()).isEqualTo("88.0bpm");
        verify(healthService).create(request);
    }

    @Test
    void createRejectsUnsupportedHealthEventType() {
        HealthEventRequest request = new HealthEventRequest(
                "user-1",
                "blood_test_report",
                Instant.parse("2026-06-12T09:00:00Z"),
                null,
                "bad",
                null,
                "manual"
        );

        ResponseEntity<HealthEventResponse> response = controller.create(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(400));
        assertThat(response.getBody()).isNull();
    }

    @Test
    void listQueriesHealthEventsByUserAndFilters() {
        Instant start = Instant.parse("2026-06-01T00:00:00Z");
        Instant end = Instant.parse("2026-06-30T23:59:59Z");
        when(healthService.list(eq("user-1"), eq(start), eq(end), eq("steps"), eq(20)))
                .thenReturn(List.of(new HealthEventResponse(
                        "event-2",
                        "user-1",
                        "steps",
                        "2026-06-12T10:00:00Z",
                        3200.0,
                        null,
                        "steps",
                        "manual",
                        "3200.0steps",
                        "2026-06-12T10:00:01Z"
                )));

        ResponseEntity<List<HealthEventResponse>> response = controller.list("user-1", start, end, "steps", 20);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().getFirst().eventType()).isEqualTo("steps");
        assertThat(response.getBody().getFirst().displayValue()).isEqualTo("3200.0steps");
        verify(healthService).list("user-1", start, end, "steps", 20);
    }

    @Test
    void listRejectsUnsupportedHealthEventType() {
        ResponseEntity<List<HealthEventResponse>> response = controller.list("user-1", null, null, "blood_test_report", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(400));
        assertThat(response.getBody()).isNull();
    }
}
