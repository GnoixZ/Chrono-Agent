package ai.chrono.backend.websocket;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class AudioStreamWebSocketConfig implements WebSocketConfigurer {
    static final int AUDIO_STREAM_MESSAGE_BUFFER_BYTES = 1024 * 1024;

    private final AudioStreamWebSocketHandler handler;

    public AudioStreamWebSocketConfig(AudioStreamWebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/audio")
                .setAllowedOriginPatterns("*");
    }

    @Bean
    ServletContextInitializer audioStreamWebSocketBufferInitializer() {
        String bufferSize = String.valueOf(AUDIO_STREAM_MESSAGE_BUFFER_BYTES);
        return servletContext -> {
            servletContext.setInitParameter(
                    "org.apache.tomcat.websocket.binaryBufferSize",
                    bufferSize
            );
            servletContext.setInitParameter(
                    "org.apache.tomcat.websocket.textBufferSize",
                    bufferSize
            );
        };
    }
}
