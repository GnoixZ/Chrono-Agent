package ai.chrono.backend.websocket;

import ai.chrono.backend.demo.DemoAudioResult;
import ai.chrono.backend.demo.DemoPipelineService;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class AudioStreamWebSocketHandler extends BinaryWebSocketHandler {
    private final JdbcTemplate jdbc;
    private final DemoPipelineService demoPipelineService;
    private final ConcurrentMap<String, StreamState> streams = new ConcurrentHashMap<>();

    public AudioStreamWebSocketHandler(JdbcTemplate jdbc, DemoPipelineService demoPipelineService) {
        this.jdbc = jdbc;
        this.demoPipelineService = demoPipelineService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String userId = queryParam(session.getUri(), "userId");
        if (userId == null || userId.isBlank()) {
            send(session, "{\"type\":\"error\",\"message\":\"userId is required\"}");
            close(session, "userId is required");
            return;
        }
        UUID streamSessionId = UUID.randomUUID();
        Instant now = Instant.now();
        try {
            jdbc.update("""
                            insert into audio_stream_session (
                                id, user_id, device_id, source_type, sample_rate, codec, started_at,
                                last_active_at, status, created_at, updated_at
                            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    streamSessionId, userId, "browser", "websocket_demo", 16000, "webm",
                    Timestamp.from(now), Timestamp.from(now), "active", Timestamp.from(now), Timestamp.from(now));
        } catch (RuntimeException error) {
            send(session, "{\"type\":\"error\",\"message\":\"active audio stream already exists or database is unavailable\"}");
            close(session, "stream open failed");
            return;
        }

        streams.put(session.getId(), new StreamState(userId, streamSessionId));
        send(session, """
                {"type":"stream_opened","streamSessionId":"%s","userId":"%s"}
                """.formatted(streamSessionId, escape(userId)).trim());
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        appendChunk(session, message.getPayload());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String payload = message.getPayload();
        if (payload != null && payload.contains("\"stop\"")) {
            stop(session, fileNameFromControl(payload));
            return;
        }
        send(session, "{\"type\":\"error\",\"message\":\"unsupported control message\"}");
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        streams.remove(session.getId());
    }

    private void appendChunk(WebSocketSession session, ByteBuffer buffer) {
        StreamState state = streams.get(session.getId());
        if (state == null) {
            send(session, "{\"type\":\"error\",\"message\":\"stream state not found\"}");
            return;
        }
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        synchronized (state.buffer()) {
            try {
                state.buffer().write(bytes);
                state.addChunk(bytes.length);
            } catch (IOException error) {
                send(session, "{\"type\":\"error\",\"message\":\"failed to buffer audio chunk\"}");
                return;
            }
        }
        jdbc.update("update audio_stream_session set last_active_at = ?, updated_at = ? where id = ?",
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), state.streamSessionId());
        send(session, """
                {"type":"chunk_received","chunks":%d,"bytes":%d}
                """.formatted(state.chunks(), state.bytes()).trim());
    }

    private void stop(WebSocketSession session, String fileName) {
        StreamState state = streams.get(session.getId());
        if (state == null) {
            send(session, "{\"type\":\"error\",\"message\":\"stream state not found\"}");
            close(session, "stream state missing");
            return;
        }
        byte[] audioBytes;
        synchronized (state.buffer()) {
            audioBytes = state.buffer().toByteArray();
        }
        if (audioBytes.length == 0) {
            send(session, "{\"type\":\"error\",\"message\":\"no audio chunks received\"}");
            closeStream(state, "empty_audio", null);
            close(session, "empty audio");
            return;
        }
        try {
            send(session, "{\"type\":\"processing_started\"}");
            DemoAudioResult result = demoPipelineService.processAudio(
                    state.userId(),
                    fileName == null || fileName.isBlank() ? "browser-stream.webm" : fileName,
                    audioBytes,
                    "websocket_stream",
                    state.streamSessionId()
            );
            closeStream(state, "client_stop", UUID.fromString(result.audioEventId()));
            send(session, """
                    {"type":"processing_completed","audioEventId":"%s","conversationMemoryId":"%s","processingStatus":"%s"}
                    """.formatted(result.audioEventId(), result.conversationMemoryId(), result.processingStatus()).trim());
            close(session, "completed");
        } catch (RuntimeException error) {
            closeStream(state, "processing_failed", null);
            send(session, """
                    {"type":"error","message":"%s"}
                    """.formatted(escape(error.getMessage())).trim());
            close(session, "processing failed");
        } finally {
            streams.remove(session.getId());
        }
    }

    private void closeStream(StreamState state, String reason, UUID audioEventId) {
        jdbc.update("""
                        update audio_stream_session
                        set status = ?, close_reason = ?, closed_at = ?, current_audio_event_id = ?, updated_at = ?
                        where id = ?
                        """,
                "closed", reason, Timestamp.from(Instant.now()), audioEventId, Timestamp.from(Instant.now()), state.streamSessionId());
    }

    private void send(WebSocketSession session, String message) {
        if (!session.isOpen()) {
            return;
        }
        try {
            session.sendMessage(new TextMessage(message));
        } catch (IOException ignored) {
            close(session, "send failed");
        }
    }

    private void close(WebSocketSession session, String reason) {
        try {
            if (session.isOpen()) {
                session.close(CloseStatus.NORMAL.withReason(reason));
            }
        } catch (IOException ignored) {
        }
    }

    private static String queryParam(URI uri, String name) {
        if (uri == null || uri.getRawQuery() == null) {
            return null;
        }
        for (String pair : uri.getRawQuery().split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2 && name.equals(URLDecoder.decode(parts[0], StandardCharsets.UTF_8))) {
                return URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static String fileNameFromControl(String message) {
        try {
            JSONObject object = JSON.parseObject(message);
            return object.getString("fileName");
        } catch (RuntimeException error) {
            return "browser-stream.webm";
        }
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static final class StreamState {
        private final String userId;
        private final UUID streamSessionId;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private int chunks;
        private long bytes;

        private StreamState(String userId, UUID streamSessionId) {
            this.userId = userId;
            this.streamSessionId = streamSessionId;
        }

        String userId() {
            return userId;
        }

        UUID streamSessionId() {
            return streamSessionId;
        }

        ByteArrayOutputStream buffer() {
            return buffer;
        }

        int chunks() {
            return chunks;
        }

        long bytes() {
            return bytes;
        }

        void addChunk(int size) {
            chunks++;
            bytes += size;
        }
    }
}
