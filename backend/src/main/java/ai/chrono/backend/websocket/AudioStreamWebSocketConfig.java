package ai.chrono.backend.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class AudioStreamWebSocketConfig implements WebSocketConfigurer {
    private final AudioStreamWebSocketHandler handler;

    public AudioStreamWebSocketConfig(AudioStreamWebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/audio")
                .setAllowedOriginPatterns("*");
    }
}
