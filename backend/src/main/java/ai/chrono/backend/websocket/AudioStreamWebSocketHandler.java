package ai.chrono.backend.websocket;

import ai.chrono.backend.demo.DemoAudioResult;
import ai.chrono.backend.demo.DemoPipelineService;
import ai.chrono.backend.modelclient.ModelServiceClient;
import ai.chrono.backend.modelclient.dto.IncrementalTranscriptRequest;
import ai.chrono.backend.modelclient.dto.IncrementalTranscriptResponse;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class AudioStreamWebSocketHandler extends BinaryWebSocketHandler {
    private static final Duration MIN_DISCONNECT_TAIL_DURATION = Duration.ofSeconds(3);
    private static final String DEFAULT_STREAM_FILE_NAME = "browser-stream.webm";
    private static final String SESSION_STATUS_LISTENING = "listening";
    private static final String SESSION_STATUS_IN_SESSION = "in_session";
    private static final String SESSION_STATUS_POST_PROCESSING = "post_processing";
    private static final String SESSION_STATUS_CLOSED = "closed";
    private static final String CLOSE_REASON_MANUAL_STOP = "manual_stop";
    private static final String CLOSE_REASON_LISTENING_STOPPED = "listening_stopped";
    private static final String CLOSE_REASON_REPLACED_BY_RECONNECT = "replaced_by_reconnect";

    private final JdbcTemplate jdbc;
    private final DemoPipelineService demoPipelineService;
    private final AudioAnalyzeTaskService audioAnalyzeTaskService;
    private final ModelServiceClient modelServiceClient;
    private final ConcurrentMap<String, StreamState> streams = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, WebSocketSession> activeSessions = new ConcurrentHashMap<>();
    private final Object activeStreamLock = new Object();

    public AudioStreamWebSocketHandler(
            JdbcTemplate jdbc,
            DemoPipelineService demoPipelineService,
            AudioAnalyzeTaskService audioAnalyzeTaskService,
            ModelServiceClient modelServiceClient
    ) {
        this.jdbc = jdbc;
        this.demoPipelineService = demoPipelineService;
        this.audioAnalyzeTaskService = audioAnalyzeTaskService;
        this.modelServiceClient = modelServiceClient;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String userId = queryParam(session.getUri(), "userId");
        if (userId == null || userId.isBlank()) {
            send(session, "{\"type\":\"error\",\"message\":\"userId is required\"}");
            close(session, "userId is required");
            return;
        }
        Instant now = Instant.now();
        UUID streamSessionId;
        List<WebSocketSession> replacedSessions;
        try {
            synchronized (activeStreamLock) {
                replacedSessions = replaceActiveStreams(userId);
                closeActiveDatabaseStreams(userId, now);
                streamSessionId = createActiveStreamSession(userId, now);
                streams.put(session.getId(), new StreamState(userId, streamSessionId, now));
                activeSessions.put(session.getId(), session);
            }
        } catch (RuntimeException error) {
            send(session, "{\"type\":\"error\",\"message\":\"database is unavailable\"}");
            close(session, "stream open failed");
            return;
        }

        for (WebSocketSession replacedSession : replacedSessions) {
            close(replacedSession, CLOSE_REASON_REPLACED_BY_RECONNECT);
        }
        send(session, """
                {"type":"stream_opened","streamSessionId":"%s","userId":"%s","sessionState":"%s","listeningStartedAt":"%s"}
                """.formatted(streamSessionId, escape(userId), SESSION_STATUS_LISTENING, now).trim());
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        appendChunk(session, message.getPayload());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String rawPayload = message.getPayload();
        JSONObject payload = parseControlMessage(rawPayload);
        String type = normalizedControlType(payload, rawPayload);
        if ("session_started".equals(type)) {
            startSession(session);
            return;
        }
        if ("close_listening".equals(type)) {
            stopListening(session);
            return;
        }
        if ("stop".equals(type)) {
            stop(session, payload.getString("fileName"), payload.getString("reason"));
            return;
        }
        if (type != null) {
            send(session, """
                    {"type":"control_ignored","message":"unsupported control message","controlType":"%s"}
                    """.formatted(escape(type)).trim());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        activeSessions.remove(session.getId());
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
        updateSessionStatus(state, SESSION_STATUS_CLOSED, null);
        closeStream(state, closeReason, latestAudioEventId);
    }

    private UUID createActiveStreamSession(String userId, Instant now) {
        UUID streamSessionId = UUID.randomUUID();
        jdbc.update("""
                        insert into audio_stream_session (
                            id, user_id, device_id, source_type, sample_rate, codec, started_at,
                            last_active_at, status, created_at, updated_at
                        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                streamSessionId, userId, "browser", "websocket_demo", 16000, "webm",
                Timestamp.from(now), Timestamp.from(now), "active", Timestamp.from(now), Timestamp.from(now));
        return streamSessionId;
    }

    private List<WebSocketSession> replaceActiveStreams(String userId) {
        List<WebSocketSession> replacedSessions = new ArrayList<>();
        for (var entry : streams.entrySet()) {
            StreamState state = entry.getValue();
            if (!userId.equals(state.userId())) {
                continue;
            }
            if (!streams.remove(entry.getKey(), state)) {
                continue;
            }
            updateSessionStatus(state, SESSION_STATUS_CLOSED, null);
            closeStream(state, CLOSE_REASON_REPLACED_BY_RECONNECT, parseUuid(state.lastAudioEventId()));
            WebSocketSession replacedSession = activeSessions.remove(entry.getKey());
            if (replacedSession != null) {
                replacedSessions.add(replacedSession);
            }
        }
        return replacedSessions;
    }

    private void closeActiveDatabaseStreams(String userId, Instant now) {
        jdbc.update("""
                        update audio_stream_session
                        set status = ?, close_reason = ?, session_close_reason = ?, closed_at = ?, session_ended_at = ?,
                            session_status = ?, updated_at = ?
                        where user_id = ? and status = ?
                        """,
                "closed", CLOSE_REASON_REPLACED_BY_RECONNECT, CLOSE_REASON_REPLACED_BY_RECONNECT,
                Timestamp.from(now), Timestamp.from(now), SESSION_STATUS_CLOSED, Timestamp.from(now),
                userId, "active");
    }

    private void appendChunk(WebSocketSession session, ByteBuffer buffer) {
        StreamState state = streams.get(session.getId());
        if (state == null) {
            send(session, "{\"type\":\"error\",\"message\":\"stream state not found\"}");
            return;
        }
        if (!state.isSessionStarted()) {
            send(session, "{\"type\":\"error\",\"message\":\"session is not started\"}");
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
        try {
            jdbc.update("update audio_stream_session set last_active_at = ?, updated_at = ? where id = ?",
                    Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), state.streamSessionId());
            send(session, """
                    {"type":"chunk_received","chunks":%d,"bytes":%d}
                    """.formatted(state.totalChunks(), state.totalBytes()).trim());
            sendIncrementalTranscript(session, state, bytes.length);
        } catch (RuntimeException error) {
            updateSessionStatus(state, SESSION_STATUS_CLOSED, null);
            closeStream(state, "processing_failed", parseUuid(state.lastAudioEventId()));
            streams.remove(session.getId());
            send(session, """
                    {"type":"error","message":"%s"}
                    """.formatted(escape(error.getMessage())).trim());
            close(session, "processing failed");
        }
    }

    private void stop(WebSocketSession session, String fileName, String reason) {
        StreamState state = streams.get(session.getId());
        if (state == null) {
            send(session, "{\"type\":\"error\",\"message\":\"stream state not found\"}");
            close(session, "stream state missing");
            return;
        }
        String resolvedFileName = fileName == null || fileName.isBlank() ? DEFAULT_STREAM_FILE_NAME : fileName;
        String closeReason = reason == null || reason.isBlank() ? CLOSE_REASON_MANUAL_STOP : trimReason(reason);
        try {
            if (!state.isSessionStarted() && !state.hasQueuedWindows() && !state.hasBufferedAudio()) {
                stopListening(session);
                return;
            }
            if (state.hasBufferedAudio()) {
                flushWindow(session, session.getId(), state, resolvedFileName, true);
            } else if (!state.hasQueuedWindows()) {
                if ("silence_timeout_60s".equals(closeReason)) {
                    finishEmptySilenceSession(session, state, closeReason);
                    return;
                }
                send(session, "{\"type\":\"error\",\"message\":\"no audio chunks received\"}");
                updateSessionStatus(state, SESSION_STATUS_CLOSED, null);
                closeStream(state, "empty_audio", null);
                streams.remove(session.getId());
                close(session, "empty audio");
                return;
            }
            state.markStopRequested(closeReason);
            updateSessionStatus(state, SESSION_STATUS_POST_PROCESSING, null);
            if (state.hasPendingTasks()) {
                send(session, """
                        {"type":"processing_started","audioEventId":"%s","pendingWindows":%d,"sessionState":"%s","closeReason":"%s"}
                        """.formatted(
                        escape(nullToEmpty(state.lastAudioEventId())),
                        state.pendingTasks(),
                        SESSION_STATUS_POST_PROCESSING,
                        escape(closeReason)).trim());
                return;
            }
            finishStop(session, state);
        } catch (RuntimeException error) {
            updateSessionStatus(state, SESSION_STATUS_CLOSED, null);
            closeStream(state, "processing_failed", parseUuid(state.lastAudioEventId()));
            streams.remove(session.getId());
            send(session, """
                    {"type":"error","message":"%s"}
                    """.formatted(escape(error.getMessage())).trim());
            close(session, "processing failed");
        }
    }

    private void startSession(WebSocketSession session) {
        StreamState state = streams.get(session.getId());
        if (state == null || state.isSessionStarted()) {
            return;
        }
        Instant now = Instant.now();
        updateSessionStatus(state, SESSION_STATUS_IN_SESSION, now);
        send(session, """
                {"type":"session_started","streamSessionId":"%s","sessionState":"%s","sessionStartedAt":"%s"}
                """.formatted(state.streamSessionId(), SESSION_STATUS_IN_SESSION, now).trim());
    }

    private void stopListening(WebSocketSession session) {
        StreamState state = streams.get(session.getId());
        if (state == null) {
            send(session, "{\"type\":\"error\",\"message\":\"stream state not found\"}");
            close(session, "stream state missing");
            return;
        }
        updateSessionStatus(state, SESSION_STATUS_CLOSED, null);
        closeStream(state, CLOSE_REASON_LISTENING_STOPPED, null);
        streams.remove(session.getId(), state);
        send(session, """
                {"type":"listening_stopped","streamSessionId":"%s","sessionState":"%s"}
                """.formatted(state.streamSessionId(), SESSION_STATUS_CLOSED).trim());
        close(session, "listening stopped");
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

    private void sendIncrementalTranscript(WebSocketSession session, StreamState state, int chunkBytes) {
        try {
            IncrementalTranscriptResponse response = modelServiceClient.incrementalTranscript(new IncrementalTranscriptRequest(
                    UUID.randomUUID().toString(),
                    state.userId(),
                    state.streamSessionId().toString(),
                    state.totalChunks(),
                    chunkBytes,
                    false
            ));
            send(session, """
                    {"type":"incremental_transcript","streamSessionId":"%s","sequence":%d,"transcript":"%s","stability":%s,"isFinal":%s}
                    """.formatted(
                    escape(nullToEmpty(response.streamSessionId())),
                    response.sequence() == null ? state.totalChunks() : response.sequence(),
                    escape(nullToEmpty(response.transcript())),
                    response.stability() == null ? "0.0" : response.stability().toString(),
                    Boolean.TRUE.equals(response.isFinal())).trim());
        } catch (RuntimeException error) {
            send(session, """
                    {"type":"transcript_error","message":"%s"}
                    """.formatted(escape(trimReason(error.getMessage()))).trim());
        }
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
        if (!state.markSessionPostProcessingStarted()) {
            return;
        }
        updateSessionStatus(state, SESSION_STATUS_POST_PROCESSING, null);
        closeStream(state, state.closeReason(), parseUuid(state.lastAudioEventId()));
        send(session, """
                {"type":"session_post_processing_started","streamSessionId":"%s","audioEventId":"%s","sessionState":"%s","closeReason":"%s"}
                """.formatted(
                state.streamSessionId(),
                escape(nullToEmpty(state.lastAudioEventId())),
                SESSION_STATUS_POST_PROCESSING,
                escape(state.closeReason())).trim());
        audioAnalyzeTaskService.submitSessionPostProcessingJob(state.streamSessionId(), state.closeReason())
                .whenComplete((result, error) -> onSessionPostProcessingFinished(session, state, result, error));
    }

    private void onSessionPostProcessingFinished(WebSocketSession session, StreamState state, DemoAudioResult result, Throwable error) {
        if (error != null || result == null) {
            markSessionPostProcessingFailed(state.streamSessionId(), error);
            send(session, """
                    {"type":"processing_completed","audioEventId":"%s","conversationMemoryId":"","processingStatus":"failed","sessionState":"%s","closeReason":"%s"}
                    """.formatted(
                    escape(nullToEmpty(state.lastAudioEventId())),
                    state.shouldContinueListeningAfterStop() ? SESSION_STATUS_LISTENING : SESSION_STATUS_CLOSED,
                    escape(state.closeReason())).trim());
            if (state.shouldContinueListeningAfterStop()) {
                reopenListeningStream(session, state);
                return;
            }
            streams.remove(session.getId(), state);
            close(session, "completed with post-processing failure");
            return;
        }
        state.markSessionPostProcessed(result);
        send(session, """
                {"type":"processing_completed","audioEventId":"%s","conversationMemoryId":"%s","processingStatus":"%s","sessionState":"%s","closeReason":"%s"}
                """.formatted(
                escape(nullToEmpty(result.audioEventId())),
                escape(nullToEmpty(result.conversationMemoryId())),
                escape(nullToEmpty(result.processingStatus())),
                state.shouldContinueListeningAfterStop() ? SESSION_STATUS_LISTENING : SESSION_STATUS_CLOSED,
                escape(state.closeReason())).trim());
        if (state.shouldContinueListeningAfterStop()) {
            reopenListeningStream(session, state);
            return;
        }
        streams.remove(session.getId(), state);
        close(session, "completed");
    }

    private void reopenListeningStream(WebSocketSession session, StreamState state) {
        Instant now = Instant.now();
        try {
            UUID nextStreamSessionId;
            synchronized (activeStreamLock) {
                nextStreamSessionId = createActiveStreamSession(state.userId(), now);
                state.resetForNextStream(nextStreamSessionId, now);
            }
            send(session, """
                    {"type":"stream_opened","streamSessionId":"%s","userId":"%s","sessionState":"%s","listeningStartedAt":"%s"}
                    """.formatted(nextStreamSessionId, escape(state.userId()), SESSION_STATUS_LISTENING, now).trim());
        } catch (RuntimeException error) {
            streams.remove(session.getId(), state);
            send(session, "{\"type\":\"error\",\"message\":\"database is unavailable\"}");
            close(session, "stream reopen failed");
        }
    }

    private void finishEmptySilenceSession(WebSocketSession session, StreamState state, String closeReason) {
        state.markStopRequested(closeReason);
        updateSessionStatus(state, SESSION_STATUS_CLOSED, null);
        closeStream(state, closeReason + ":empty_audio", null);
        send(session, """
                {"type":"processing_completed","audioEventId":"","conversationMemoryId":"","processingStatus":"discarded","sessionState":"%s","closeReason":"%s"}
                """.formatted(SESSION_STATUS_LISTENING, escape(closeReason)).trim());
        reopenListeningStream(session, state);
    }

    private void markSessionPostProcessingFailed(UUID streamSessionId, Throwable error) {
        jdbc.update("""
                        update audio_stream_session
                        set session_post_processing_status = ?, session_status = ?, updated_at = ?
                        where id = ?
                        """,
                "failed", SESSION_STATUS_CLOSED, Timestamp.from(Instant.now()), streamSessionId);
    }

    private void closeStream(StreamState state, String reason, UUID audioEventId) {
        if (state.isClosedPersisted()) {
            return;
        }
        jdbc.update("""
                        update audio_stream_session
                        set status = ?, close_reason = ?, session_close_reason = ?, closed_at = ?, session_ended_at = ?,
                            current_audio_event_id = ?, updated_at = ?
                        where id = ?
                        """,
                "closed", reason, reason, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()),
                audioEventId, Timestamp.from(Instant.now()), state.streamSessionId());
        state.markClosedPersisted();
    }

    private void updateSessionStatus(StreamState state, String sessionStatus, Instant sessionStartedAt) {
        if (state == null) {
            return;
        }
        state.markSessionStatus(sessionStatus, sessionStartedAt);
        if (sessionStartedAt != null) {
            jdbc.update("""
                            update audio_stream_session
                            set session_status = ?, session_started_at = ?, updated_at = ?
                            where id = ?
                            """,
                    sessionStatus, Timestamp.from(sessionStartedAt), Timestamp.from(Instant.now()), state.streamSessionId());
            return;
        }
        jdbc.update("""
                        update audio_stream_session
                        set session_status = ?, updated_at = ?
                        where id = ?
                        """,
                sessionStatus, Timestamp.from(Instant.now()), state.streamSessionId());
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

    private static JSONObject parseControlMessage(String message) {
        try {
            JSONObject object = JSONObject.parseObject(message);
            return object == null ? new JSONObject() : object;
        } catch (RuntimeException error) {
            return new JSONObject();
        }
    }

    private static String normalizedControlType(JSONObject payload, String rawPayload) {
        String value = firstNonBlank(
                payload.getString("type"),
                payload.getString("event"),
                payload.getString("action"),
                rawPayload
        );
        if (value == null) {
            return null;
        }
        String normalized = value.trim()
                .replace("\"", "")
                .replace("-", "_")
                .toLowerCase();
        return switch (normalized) {
            case "start", "start_session", "session_start", "session_started" -> "session_started";
            case "close", "close_listening", "listening_stop", "stop_listening" -> "close_listening";
            case "stop", "manual_stop", "silence_timeout_60s" -> "stop";
            default -> normalized;
        };
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
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
        private UUID streamSessionId;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private Instant currentWindowStartedAt;
        private int currentWindowIndex = 1;
        private int totalChunks;
        private long totalBytes;
        private int currentWindowBytes;
        private int queuedWindows;
        private int pendingTasks;
        private boolean stopRequested;
        private boolean sessionPostProcessingStarted;
        private String closeReason = CLOSE_REASON_MANUAL_STOP;
        private Instant sessionStartedAt;
        private String sessionStatus = SESSION_STATUS_LISTENING;
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

        synchronized void markStopRequested(String reason) {
            stopRequested = true;
            closeReason = reason == null || reason.isBlank() ? CLOSE_REASON_MANUAL_STOP : reason;
        }

        synchronized String closeReason() {
            return closeReason;
        }

        synchronized boolean shouldContinueListeningAfterStop() {
            return "silence_timeout_60s".equals(closeReason);
        }

        synchronized boolean markSessionPostProcessingStarted() {
            if (sessionPostProcessingStarted) {
                return false;
            }
            sessionPostProcessingStarted = true;
            return true;
        }

        synchronized void markSessionPostProcessed(DemoAudioResult result) {
            lastAudioEventId = result.audioEventId();
            lastConversationMemoryId = result.conversationMemoryId();
            lastProcessingStatus = result.processingStatus();
        }

        synchronized boolean isSessionStarted() {
            return sessionStartedAt != null;
        }

        synchronized void markSessionStatus(String nextSessionStatus, Instant startedAt) {
            sessionStatus = nextSessionStatus;
            if (startedAt != null && sessionStartedAt == null) {
                sessionStartedAt = startedAt;
            }
        }

        synchronized boolean isClosedPersisted() {
            return closedPersisted;
        }

        synchronized void markClosedPersisted() {
            closedPersisted = true;
        }

        synchronized void resetForNextStream(UUID nextStreamSessionId, Instant startedAt) {
            streamSessionId = nextStreamSessionId;
            buffer.reset();
            currentWindowStartedAt = startedAt;
            currentWindowIndex = 1;
            totalChunks = 0;
            totalBytes = 0;
            currentWindowBytes = 0;
            queuedWindows = 0;
            pendingTasks = 0;
            stopRequested = false;
            sessionPostProcessingStarted = false;
            closeReason = CLOSE_REASON_MANUAL_STOP;
            sessionStartedAt = null;
            sessionStatus = SESSION_STATUS_LISTENING;
            lastAudioEventId = null;
            lastConversationMemoryId = null;
            lastProcessingStatus = null;
            closedPersisted = false;
        }
    }
}
