package ai.chrono.backend.websocket;

import ai.chrono.backend.demo.DemoAudioResult;
import ai.chrono.backend.demo.DemoPipelineService;
import ai.chrono.backend.task.AudioAnalyzeTaskService;
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
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class AudioStreamWebSocketHandler extends BinaryWebSocketHandler {
    private static final Duration WINDOW_DURATION = Duration.ofSeconds(30);
    private static final Duration MIN_DISCONNECT_TAIL_DURATION = Duration.ofSeconds(3);
    private static final String DEFAULT_STREAM_FILE_NAME = "browser-stream.webm";

    private final JdbcTemplate jdbc;
    private final DemoPipelineService demoPipelineService;
    private final AudioAnalyzeTaskService audioAnalyzeTaskService;
    private final ConcurrentMap<String, StreamState> streams = new ConcurrentHashMap<>();

    public AudioStreamWebSocketHandler(
            JdbcTemplate jdbc,
            DemoPipelineService demoPipelineService,
            AudioAnalyzeTaskService audioAnalyzeTaskService
    ) {
        this.jdbc = jdbc;
        this.demoPipelineService = demoPipelineService;
        this.audioAnalyzeTaskService = audioAnalyzeTaskService;
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

        streams.put(session.getId(), new StreamState(userId, streamSessionId, now));
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
        StreamState state = streams.remove(session.getId());
        if (state == null || state.isClosedPersisted()) {
            return;
        }
        UUID latestAudioEventId = parseUuid(state.lastAudioEventId());
        String closeReason = closeReasonFrom(status);
        try {
            if (state.hasBufferedAudio() && state.currentWindowDuration(Instant.now()).compareTo(MIN_DISCONNECT_TAIL_DURATION) >= 0) {
                flushWindow(null, session.getId(), state, DEFAULT_STREAM_FILE_NAME, true);
                latestAudioEventId = parseUuid(state.lastAudioEventId());
            } else if (state.hasBufferedAudio()) {
                closeReason = closeReason + ":abandoned_tail_window";
            }
        } catch (RuntimeException error) {
            closeReason = "processing_failed:" + trimReason(error.getMessage());
        }
        closeStream(state, closeReason, latestAudioEventId);
    }

    private void appendChunk(WebSocketSession session, ByteBuffer buffer) {
        StreamState state = streams.get(session.getId());
        if (state == null) {
            send(session, "{\"type\":\"error\",\"message\":\"stream state not found\"}");
            return;
        }
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        try {
            state.writeChunk(bytes);
        } catch (IOException error) {
            send(session, "{\"type\":\"error\",\"message\":\"failed to buffer audio chunk\"}");
            return;
        }
        jdbc.update("update audio_stream_session set last_active_at = ?, updated_at = ? where id = ?",
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), state.streamSessionId());
        send(session, """
                {"type":"chunk_received","chunks":%d,"bytes":%d}
                """.formatted(state.totalChunks(), state.totalBytes()).trim());

        if (state.currentWindowDuration(Instant.now()).compareTo(WINDOW_DURATION) < 0) {
            return;
        }

        try {
            flushWindow(session, session.getId(), state, DEFAULT_STREAM_FILE_NAME, false);
        } catch (RuntimeException error) {
            closeStream(state, "processing_failed", parseUuid(state.lastAudioEventId()));
            streams.remove(session.getId());
            send(session, """
                    {"type":"error","message":"%s"}
                    """.formatted(escape(error.getMessage())).trim());
            close(session, "processing failed");
        }
    }

    private void stop(WebSocketSession session, String fileName) {
        StreamState state = streams.get(session.getId());
        if (state == null) {
            send(session, "{\"type\":\"error\",\"message\":\"stream state not found\"}");
            close(session, "stream state missing");
            return;
        }
        String resolvedFileName = fileName == null || fileName.isBlank() ? DEFAULT_STREAM_FILE_NAME : fileName;
        try {
            if (state.hasBufferedAudio()) {
                flushWindow(session, session.getId(), state, resolvedFileName, true);
            } else if (!state.hasQueuedWindows()) {
                send(session, "{\"type\":\"error\",\"message\":\"no audio chunks received\"}");
                closeStream(state, "empty_audio", null);
                streams.remove(session.getId());
                close(session, "empty audio");
                return;
            }
            state.markStopRequested();
            if (state.hasPendingTasks()) {
                send(session, """
                        {"type":"processing_started","audioEventId":"%s","pendingWindows":%d}
                        """.formatted(
                        escape(nullToEmpty(state.lastAudioEventId())),
                        state.pendingTasks()).trim());
                return;
            }
            finishStop(session, state);
        } catch (RuntimeException error) {
            closeStream(state, "processing_failed", parseUuid(state.lastAudioEventId()));
            streams.remove(session.getId());
            send(session, """
                    {"type":"error","message":"%s"}
                    """.formatted(escape(error.getMessage())).trim());
            close(session, "processing failed");
        }
    }

    private void flushWindow(WebSocketSession session, String sessionKey, StreamState state, String fileName, boolean finalWindow) {
        byte[] audioBytes = state.currentWindowBytes();
        if (audioBytes.length == 0) {
            throw new IllegalStateException("no audio chunks received");
        }
        Instant windowEndedAt = Instant.now();
        int windowIndex = state.currentWindowIndex();
        DemoPipelineService.AudioWindowMetadata windowMetadata = new DemoPipelineService.AudioWindowMetadata(
                windowIndex,
                state.currentWindowStartedAt(),
                windowEndedAt,
                finalWindow
        );
        String windowFileName = windowFileName(fileName, windowIndex, finalWindow);
        DemoPipelineService.PendingAudioAnalysis pendingAudioAnalysis = demoPipelineService.enqueueAudioAnalysis(
                state.userId(),
                windowFileName,
                audioBytes,
                "websocket_stream",
                state.streamSessionId(),
                windowMetadata
        );
        enqueueWindow(state.streamSessionId(), pendingAudioAnalysis.audioEventId());
        state.markWindowQueued(pendingAudioAnalysis, windowEndedAt);
        if (session != null) {
            send(session, """
                    {"type":"window_processing_started","audioEventId":"%s","windowIndex":%d,"pendingWindows":%d}
                    """.formatted(
                    escape(pendingAudioAnalysis.audioEventId()),
                    windowIndex,
                    state.pendingTasks()).trim());
        }
        UUID audioEventId = UUID.fromString(pendingAudioAnalysis.audioEventId());
        audioAnalyzeTaskService.submitAudioAnalyzeJob(audioEventId)
                .whenComplete((result, error) -> onWindowAnalysisFinished(session, sessionKey, state.streamSessionId(), audioEventId, result, error));
    }

    private void onWindowAnalysisFinished(
            WebSocketSession session,
            String sessionKey,
            UUID streamSessionId,
            UUID audioEventId,
            DemoAudioResult result,
            Throwable error
    ) {
        StreamState state = streams.get(sessionKey);
        if (error == null && result != null) {
            markWindowProcessed(streamSessionId);
            if (state != null) {
                boolean shouldFinishStop = state.markWindowProcessed(result);
                send(session, """
                        {"type":"window_processing_completed","audioEventId":"%s","conversationMemoryId":"%s","processingStatus":"%s"}
                        """.formatted(
                        escape(result.audioEventId()),
                        escape(nullToEmpty(result.conversationMemoryId())),
                        escape(nullToEmpty(result.processingStatus()))).trim());
                if (shouldFinishStop) {
                    finishStop(session, state);
                }
            }
            return;
        }

        markWindowFailed(streamSessionId, audioEventId);
        if (state != null) {
            boolean shouldFinishStop = state.markWindowFailed(audioEventId.toString());
            String message = error == null ? "unknown processing error" : trimReason(error.getMessage());
            send(session, """
                    {"type":"error","message":"%s"}
                    """.formatted(escape(message)).trim());
            if (shouldFinishStop) {
                finishStop(session, state);
            }
        }
    }

    private void finishStop(WebSocketSession session, StreamState state) {
        closeStream(state, "client_stop", parseUuid(state.lastAudioEventId()));
        streams.remove(session.getId(), state);
        send(session, """
                {"type":"processing_completed","audioEventId":"%s","conversationMemoryId":"%s","processingStatus":"%s"}
                """.formatted(
                escape(nullToEmpty(state.lastAudioEventId())),
                escape(nullToEmpty(state.lastConversationMemoryId())),
                escape(nullToEmpty(state.lastProcessingStatus()))).trim());
        close(session, "completed");
    }

    private void closeStream(StreamState state, String reason, UUID audioEventId) {
        if (state.isClosedPersisted()) {
            return;
        }
        jdbc.update("""
                        update audio_stream_session
                        set status = ?, close_reason = ?, closed_at = ?, current_audio_event_id = ?, updated_at = ?
                        where id = ?
                        """,
                "closed", reason, Timestamp.from(Instant.now()), audioEventId, Timestamp.from(Instant.now()), state.streamSessionId());
        state.markClosedPersisted();
    }

    private void enqueueWindow(UUID streamSessionId, String audioEventId) {
        jdbc.update("""
                        update audio_stream_session
                        set current_audio_event_id = ?,
                            window_count = window_count + 1,
                            updated_at = ?
                        where id = ?
                        """,
                parseUuid(audioEventId), Timestamp.from(Instant.now()), streamSessionId);
    }

    private void markWindowProcessed(UUID streamSessionId) {
        jdbc.update("""
                        update audio_stream_session
                        set processed_window_count = processed_window_count + 1,
                            updated_at = ?
                        where id = ?
                        """,
                Timestamp.from(Instant.now()), streamSessionId);
    }

    private void markWindowFailed(UUID streamSessionId, UUID audioEventId) {
        jdbc.update("""
                        update audio_stream_session
                        set current_audio_event_id = ?,
                            failed_window_count = failed_window_count + 1,
                            updated_at = ?
                        where id = ?
                        """,
                audioEventId, Timestamp.from(Instant.now()), streamSessionId);
    }

    private void send(WebSocketSession session, String message) {
        if (session == null || !session.isOpen()) {
            return;
        }
        synchronized (session) {
            if (!session.isOpen()) {
                return;
            }
            try {
                session.sendMessage(new TextMessage(message));
            } catch (IOException ignored) {
                close(session, "send failed");
            }
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
            JSONObject object = JSONObject.parseObject(message);
            return object.getString("fileName");
        } catch (RuntimeException error) {
            return DEFAULT_STREAM_FILE_NAME;
        }
    }

    private static String closeReasonFrom(CloseStatus status) {
        if (status == null) {
            return "connection_closed";
        }
        String reason = status.getReason();
        if (reason == null || reason.isBlank()) {
            return "connection_closed:" + status.getCode();
        }
        return "connection_closed:" + status.getCode() + ":" + trimReason(reason);
    }

    private static String windowFileName(String originalFileName, int windowIndex, boolean finalWindow) {
        String baseFileName = (originalFileName == null || originalFileName.isBlank()) ? DEFAULT_STREAM_FILE_NAME : originalFileName;
        int dotIndex = baseFileName.lastIndexOf('.');
        String suffix = finalWindow ? ".final-%03d".formatted(windowIndex) : ".part-%03d".formatted(windowIndex);
        if (dotIndex < 0) {
            return baseFileName + suffix;
        }
        return baseFileName.substring(0, dotIndex) + suffix + baseFileName.substring(dotIndex);
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String trimReason(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.length() > 240 ? value.substring(0, 240) : value;
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
        private Instant currentWindowStartedAt;
        private int currentWindowIndex = 1;
        private int totalChunks;
        private long totalBytes;
        private int currentWindowBytes;
        private int queuedWindows;
        private int pendingTasks;
        private boolean stopRequested;
        private String lastAudioEventId;
        private String lastConversationMemoryId;
        private String lastProcessingStatus;
        private boolean closedPersisted;

        private StreamState(String userId, UUID streamSessionId, Instant startedAt) {
            this.userId = userId;
            this.streamSessionId = streamSessionId;
            this.currentWindowStartedAt = startedAt;
        }

        String userId() {
            return userId;
        }

        UUID streamSessionId() {
            return streamSessionId;
        }

        synchronized void writeChunk(byte[] bytes) throws IOException {
            buffer.write(bytes);
            totalChunks++;
            totalBytes += bytes.length;
            currentWindowBytes += bytes.length;
        }

        synchronized byte[] currentWindowBytes() {
            return buffer.toByteArray();
        }

        synchronized boolean hasBufferedAudio() {
            return currentWindowBytes > 0;
        }

        synchronized Duration currentWindowDuration(Instant now) {
            return Duration.between(currentWindowStartedAt, now);
        }

        synchronized int currentWindowIndex() {
            return currentWindowIndex;
        }

        synchronized Instant currentWindowStartedAt() {
            return currentWindowStartedAt;
        }

        synchronized int totalChunks() {
            return totalChunks;
        }

        synchronized long totalBytes() {
            return totalBytes;
        }

        synchronized boolean hasQueuedWindows() {
            return queuedWindows > 0;
        }

        synchronized int pendingTasks() {
            return pendingTasks;
        }

        synchronized boolean hasPendingTasks() {
            return pendingTasks > 0;
        }

        synchronized String lastAudioEventId() {
            return lastAudioEventId;
        }

        synchronized String lastConversationMemoryId() {
            return lastConversationMemoryId;
        }

        synchronized String lastProcessingStatus() {
            return lastProcessingStatus;
        }

        synchronized void markWindowQueued(DemoPipelineService.PendingAudioAnalysis pendingAudioAnalysis, Instant nextWindowStartedAt) {
            buffer.reset();
            currentWindowStartedAt = nextWindowStartedAt;
            currentWindowIndex++;
            currentWindowBytes = 0;
            queuedWindows++;
            pendingTasks++;
            lastAudioEventId = pendingAudioAnalysis.audioEventId();
            lastProcessingStatus = pendingAudioAnalysis.processingStatus();
        }

        synchronized boolean markWindowProcessed(DemoAudioResult result) {
            pendingTasks = Math.max(0, pendingTasks - 1);
            lastAudioEventId = result.audioEventId();
            lastConversationMemoryId = result.conversationMemoryId();
            lastProcessingStatus = result.processingStatus();
            return stopRequested && pendingTasks == 0;
        }

        synchronized boolean markWindowFailed(String audioEventId) {
            pendingTasks = Math.max(0, pendingTasks - 1);
            lastAudioEventId = audioEventId;
            lastConversationMemoryId = null;
            lastProcessingStatus = "failed";
            return stopRequested && pendingTasks == 0;
        }

        synchronized void markStopRequested() {
            stopRequested = true;
        }

        synchronized boolean isClosedPersisted() {
            return closedPersisted;
        }

        synchronized void markClosedPersisted() {
            closedPersisted = true;
        }
    }
}
