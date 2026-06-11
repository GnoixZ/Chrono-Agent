package ai.chrono.backend.audio;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AudioStreamServiceTest {
    @Test
    void rejectsSecondActiveStreamForSameUser() {
        AudioStreamService service = new AudioStreamService();

        AudioStreamSessionResponse first = service.open("user-1");

        assertThat(first.status()).isEqualTo("active");
        assertThatThrownBy(() -> service.open("user-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("active audio stream already exists");
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
