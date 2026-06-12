package ai.chrono.backend.audio;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AudioStreamServiceTest {
    @Test
    void replacesActiveStreamForSameUser() {
        AudioStreamService service = new AudioStreamService();

        AudioStreamSessionResponse first = service.open("user-1");
        AudioStreamSessionResponse second = service.open("user-1");

        assertThat(first.status()).isEqualTo("active");
        assertThat(second.status()).isEqualTo("active");
        assertThat(second.streamSessionId()).isNotEqualTo(first.streamSessionId());
    }

    @Test
    void allowsNewStreamAfterClose() {
        AudioStreamService service = new AudioStreamService();
        AudioStreamSessionResponse first = service.open("user-1");

        service.close("user-1", first.streamSessionId());
        AudioStreamSessionResponse second = service.open("user-1");

        assertThat(second.status()).isEqualTo("active");
    }
}
