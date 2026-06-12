package ai.chrono.backend.websocket;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockServletContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AudioStreamWebSocketConfigTest {
    @Test
    void configuresAudioStreamMessageBuffersAboveBrowserChunkSize() throws Exception {
        AudioStreamWebSocketConfig config = new AudioStreamWebSocketConfig(mock(AudioStreamWebSocketHandler.class));
        MockServletContext servletContext = new MockServletContext();

        config.audioStreamWebSocketBufferInitializer().onStartup(servletContext);

        assertThat(servletContext.getInitParameter("org.apache.tomcat.websocket.binaryBufferSize"))
                .isEqualTo(String.valueOf(AudioStreamWebSocketConfig.AUDIO_STREAM_MESSAGE_BUFFER_BYTES));
        assertThat(servletContext.getInitParameter("org.apache.tomcat.websocket.textBufferSize"))
                .isEqualTo(String.valueOf(AudioStreamWebSocketConfig.AUDIO_STREAM_MESSAGE_BUFFER_BYTES));
        assertThat(AudioStreamWebSocketConfig.AUDIO_STREAM_MESSAGE_BUFFER_BYTES).isGreaterThan(64 * 1024);
    }
}
