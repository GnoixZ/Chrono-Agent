package ai.chrono.backend.websocket;

import ai.chrono.backend.demo.DemoAudioResult;
import ai.chrono.backend.demo.DemoPipelineService;
import ai.chrono.backend.modelclient.ModelServiceClient;
import ai.chrono.backend.modelclient.dto.IncrementalTranscriptRequest;
import ai.chrono.backend.modelclient.dto.IncrementalTranscriptResponse;
import ai.chrono.backend.task.AudioAnalyzeTaskService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.lang.reflect.Field;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AudioStreamWebSocketHandlerTest {
    @Test
    void stopSplitsLongStreamIntoMultipleWindows() throws Exception {
        TestFixture fixture = new TestFixture();
        fixture.pipelineResults.put(fixture.audioEventIds.get(0), completedResult(fixture.audioEventIds.get(0), "memory-1"));
        fixture.pipelineResults.put(fixture.audioEventIds.get(1), completedResult(fixture.audioEventIds.get(1), "memory-2"));

        fixture.handler.afterConnectionEstablished(fixture.session);
        fixture.handler.handleTextMessage(fixture.session, new TextMessage("{\"type\":\"session_started\"}"));
        fixture.handler.handleBinaryMessage(fixture.session, new BinaryMessage(chunk()));
        fixture.setCurrentWindowStartedAtSecondsAgo(31);
        fixture.handler.handleBinaryMessage(fixture.session, new BinaryMessage(chunk()));
        fixture.handler.handleBinaryMessage(fixture.session, new BinaryMessage(chunk()));
        fixture.handler.handleTextMessage(fixture.session, new TextMessage("{\"type\":\"stop\",\"fileName\":\"long-run.webm\"}"));

        assertThat(fixture.windowMetadata).hasSize(2);
        assertThat(fixture.windowMetadata.get(0).windowIndex()).isEqualTo(1);
        assertThat(fixture.windowMetadata.get(0).finalWindow()).isFalse();
        assertThat(fixture.windowMetadata.get(1).windowIndex()).isEqualTo(2);
        assertThat(fixture.windowMetadata.get(1).finalWindow()).isTrue();
        assertThat(fixture.fileNames).containsExactly("browser-stream.part-001.webm", "long-run.final-002.webm");
        assertThat(fixture.outboundMessages.stream().filter(message -> message.contains("\"type\":\"window_processing_started\"")).count()).isEqualTo(2);
        assertThat(fixture.outboundMessages.stream().filter(message -> message.contains("\"type\":\"window_processing_completed\"")).count()).isEqualTo(2);
        assertThat(fixture.outboundMessages.stream().filter(message -> message.contains("\"type\":\"incremental_transcript\"")).count()).isEqualTo(3);
        assertThat(fixture.outboundMessages.stream().anyMatch(message -> message.contains("\"type\":\"processing_completed\""))).isTrue();
        assertThat(fixture.closeReasons()).contains("manual_stop");

        verify(fixture.audioAnalyzeTaskService, times(2)).submitAudioAnalyzeJob(any(UUID.class));
        verify(fixture.audioAnalyzeTaskService).submitSessionPostProcessingJob(any(UUID.class), anyString());
    }

    @Test
    void abnormalDisconnectFlushesTailWindowWhenLongEnough() throws Exception {
        TestFixture fixture = new TestFixture();
        fixture.pipelineResults.put(fixture.audioEventIds.get(0), completedResult(fixture.audioEventIds.get(0), "memory-disconnect"));

        fixture.handler.afterConnectionEstablished(fixture.session);
        fixture.handler.handleTextMessage(fixture.session, new TextMessage("{\"type\":\"session_started\"}"));
        fixture.handler.handleBinaryMessage(fixture.session, new BinaryMessage(chunk()));
        fixture.setCurrentWindowStartedAtSecondsAgo(4);
        fixture.handler.afterConnectionClosed(fixture.session, new CloseStatus(1001, "network_lost"));

        assertThat(fixture.windowMetadata).hasSize(1);
        assertThat(fixture.windowMetadata.get(0).windowIndex()).isEqualTo(1);
        assertThat(fixture.windowMetadata.get(0).finalWindow()).isTrue();
        assertThat(fixture.fileNames).containsExactly("browser-stream.final-001.webm");
        assertThat(fixture.closeReasons()).contains("connection_closed:1001:network_lost");
        assertThat(fixture.sqlStatements()).anyMatch(sql -> sql.contains("processed_window_count = processed_window_count + 1"));

        verify(fixture.audioAnalyzeTaskService).submitAudioAnalyzeJob(any(UUID.class));
    }

    @Test
    void abnormalDisconnectAbandonsShortTailWindow() throws Exception {
        TestFixture fixture = new TestFixture();

        fixture.handler.afterConnectionEstablished(fixture.session);
        fixture.handler.handleTextMessage(fixture.session, new TextMessage("{\"type\":\"session_started\"}"));
        fixture.handler.handleBinaryMessage(fixture.session, new BinaryMessage(chunk()));
        fixture.handler.afterConnectionClosed(fixture.session, new CloseStatus(1001, "network_lost"));

        assertThat(fixture.windowMetadata).isEmpty();
        assertThat(fixture.closeReasons()).contains("connection_closed:1001:network_lost:abandoned_tail_window");
        assertThat(fixture.sqlStatements()).noneMatch(sql -> sql.contains("window_count = window_count + 1"));

        verifyNoInteractions(fixture.audioAnalyzeTaskService);
    }

    @Test
    void binaryChunkBeforeSessionStartIsRejected() throws Exception {
        TestFixture fixture = new TestFixture();

        fixture.handler.afterConnectionEstablished(fixture.session);
        fixture.handler.handleBinaryMessage(fixture.session, new BinaryMessage(chunk()));

        assertThat(fixture.outboundMessages).anyMatch(message -> message.contains("session is not started"));
        assertThat(fixture.windowMetadata).isEmpty();
        verifyNoInteractions(fixture.audioAnalyzeTaskService);
    }

    @Test
    void startAliasControlMessageStartsSession() throws Exception {
        TestFixture fixture = new TestFixture();

        fixture.handler.afterConnectionEstablished(fixture.session);
        fixture.handler.handleTextMessage(fixture.session, new TextMessage("{\"type\":\"start\"}"));

        assertThat(fixture.outboundMessages).anyMatch(message -> message.contains("\"type\":\"session_started\""));
    }

    @Test
    void unknownControlMessageIsIgnoredWithoutError() throws Exception {
        TestFixture fixture = new TestFixture();

        fixture.handler.afterConnectionEstablished(fixture.session);
        fixture.handler.handleTextMessage(fixture.session, new TextMessage("{\"type\":\"noop\"}"));

        assertThat(fixture.outboundMessages).anyMatch(message -> message.contains("\"type\":\"control_ignored\""));
        assertThat(fixture.outboundMessages).noneMatch(message -> message.contains("\"type\":\"error\""));
    }

    @Test
    void reconnectReplacesExistingActiveStreamForUser() throws Exception {
        TestFixture fixture = new TestFixture();
        WebSocketSession reconnectSession = fixture.newSession("session-2", "test-user");

        fixture.handler.afterConnectionEstablished(fixture.session);
        fixture.handler.afterConnectionEstablished(reconnectSession);

        assertThat(fixture.outboundMessages.stream().filter(message -> message.contains("\"type\":\"stream_opened\"")).count()).isEqualTo(2);
        assertThat(fixture.outboundMessages).noneMatch(message -> message.contains("active audio stream already exists"));
        assertThat(fixture.closeReasons()).contains("replaced_by_reconnect");
        verify(fixture.session).close(any(CloseStatus.class));
    }

    @Test
    void automaticSilenceStopPersistsCloseReason() throws Exception {
        TestFixture fixture = new TestFixture();
        fixture.pipelineResults.put(fixture.audioEventIds.get(0), completedResult(fixture.audioEventIds.get(0), null));

        fixture.handler.afterConnectionEstablished(fixture.session);
        fixture.handler.handleTextMessage(fixture.session, new TextMessage("{\"type\":\"session_started\"}"));
        fixture.handler.handleBinaryMessage(fixture.session, new BinaryMessage(chunk()));
        fixture.handler.handleTextMessage(fixture.session, new TextMessage("{\"type\":\"stop\",\"reason\":\"silence_timeout_60s\"}"));

        assertThat(fixture.closeReasons()).contains("silence_timeout_60s");
        assertThat(fixture.outboundMessages).anyMatch(message ->
                message.contains("\"type\":\"processing_completed\"")
                        && message.contains("\"sessionState\":\"listening\"")
                        && message.contains("\"closeReason\":\"silence_timeout_60s\""));
        assertThat(fixture.outboundMessages.stream().filter(message -> message.contains("\"type\":\"stream_opened\"")).count()).isEqualTo(2);
        verify(fixture.session, times(0)).close(any(CloseStatus.class));
    }

    @Test
    void silenceStopWithoutChunksKeepsWebSocketListening() throws Exception {
        TestFixture fixture = new TestFixture();

        fixture.handler.afterConnectionEstablished(fixture.session);
        fixture.handler.handleTextMessage(fixture.session, new TextMessage("{\"type\":\"session_started\"}"));
        fixture.handler.handleTextMessage(fixture.session, new TextMessage("{\"type\":\"stop\",\"reason\":\"silence_timeout_60s\"}"));

        assertThat(fixture.closeReasons()).contains("silence_timeout_60s:empty_audio");
        assertThat(fixture.outboundMessages).anyMatch(message ->
                message.contains("\"type\":\"processing_completed\"")
                        && message.contains("\"processingStatus\":\"discarded\"")
                        && message.contains("\"sessionState\":\"listening\""));
        assertThat(fixture.outboundMessages.stream().filter(message -> message.contains("\"type\":\"stream_opened\"")).count()).isEqualTo(2);
        verify(fixture.session, times(0)).close(any(CloseStatus.class));
        verifyNoInteractions(fixture.audioAnalyzeTaskService);
    }

    private static DemoAudioResult completedResult(UUID audioEventId, String conversationMemoryId) {
        return new DemoAudioResult(
                audioEventId.toString(),
                UUID.randomUUID().toString(),
                conversationMemoryId,
                "completed",
                "title",
                "overview",
                List.of(),
                List.of(),
                List.of()
        );
    }

    private static byte[] chunk() {
        byte[] bytes = new byte[1024];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = 7;
        }
        return bytes;
    }

    private static final class TestFixture {
        private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
        private final DemoPipelineService demoPipelineService = mock(DemoPipelineService.class);
        private final AudioAnalyzeTaskService audioAnalyzeTaskService = mock(AudioAnalyzeTaskService.class);
        private final ModelServiceClient modelServiceClient = mock(ModelServiceClient.class);
        private final AudioStreamWebSocketHandler handler = new AudioStreamWebSocketHandler(jdbc, demoPipelineService, audioAnalyzeTaskService, modelServiceClient);
        private final WebSocketSession session = mock(WebSocketSession.class);
        private final List<String> outboundMessages = new ArrayList<>();
        private final List<DemoPipelineService.AudioWindowMetadata> windowMetadata = new ArrayList<>();
        private final List<String> fileNames = new ArrayList<>();
        private final List<UUID> audioEventIds = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        private final Map<UUID, DemoAudioResult> pipelineResults = new java.util.HashMap<>();
        private int enqueueIndex;

        private TestFixture() {
            try {
                configureSession(session, "session-1", "test-user");
                when(demoPipelineService.enqueueAudioAnalysis(anyString(), anyString(), any(byte[].class), anyString(), any(UUID.class), any(DemoPipelineService.AudioWindowMetadata.class)))
                        .thenAnswer(invocation -> {
                            fileNames.add(invocation.getArgument(1, String.class));
                            windowMetadata.add(invocation.getArgument(5, DemoPipelineService.AudioWindowMetadata.class));
                            UUID audioEventId = audioEventIds.get(enqueueIndex++);
                            return new DemoPipelineService.PendingAudioAnalysis(audioEventId.toString(), UUID.randomUUID().toString(), "processing");
                        });
                when(audioAnalyzeTaskService.submitAudioAnalyzeJob(any(UUID.class))).thenAnswer(invocation -> {
                    UUID audioEventId = invocation.getArgument(0, UUID.class);
                    DemoAudioResult result = pipelineResults.get(audioEventId);
                    if (result == null) {
                        return CompletableFuture.failedFuture(new IllegalStateException("missing result for " + audioEventId));
                    }
                    return CompletableFuture.completedFuture(result);
                });
                when(audioAnalyzeTaskService.submitSessionPostProcessingJob(any(UUID.class), anyString())).thenReturn(CompletableFuture.completedFuture(
                        new DemoAudioResult(
                                audioEventIds.get(0).toString(),
                                null,
                                "session-memory",
                                "completed",
                                "session title",
                                "session overview",
                                List.of(),
                                List.of(),
                                List.of()
                        )
                ));
                when(modelServiceClient.incrementalTranscript(any(IncrementalTranscriptRequest.class))).thenAnswer(invocation -> {
                    IncrementalTranscriptRequest request = invocation.getArgument(0, IncrementalTranscriptRequest.class);
                    return new IncrementalTranscriptResponse(
                            request.streamSessionId(),
                            request.chunkIndex(),
                            "chunk " + request.chunkIndex(),
                            0.72,
                            false
                    );
                });
            } catch (Exception error) {
                throw new IllegalStateException(error);
            }
        }

        private WebSocketSession newSession(String id, String userId) throws Exception {
            WebSocketSession nextSession = mock(WebSocketSession.class);
            configureSession(nextSession, id, userId);
            return nextSession;
        }

        private void configureSession(WebSocketSession targetSession, String id, String userId) throws Exception {
            when(targetSession.getId()).thenReturn(id);
            when(targetSession.getUri()).thenReturn(URI.create("ws://localhost/ws/audio?userId=" + userId));
            when(targetSession.isOpen()).thenReturn(true);
            doAnswer(invocation -> {
                outboundMessages.add(((TextMessage) invocation.getArgument(0)).getPayload());
                return null;
            }).when(targetSession).sendMessage(any(TextMessage.class));
            doAnswer(invocation -> null).when(targetSession).close(any(CloseStatus.class));
        }

        private void setCurrentWindowStartedAtSecondsAgo(long secondsAgo) throws Exception {
            Object streamState = streamState();
            assertThat(streamState).isNotNull();
            Field currentWindowStartedAt = streamState.getClass().getDeclaredField("currentWindowStartedAt");
            currentWindowStartedAt.setAccessible(true);
            currentWindowStartedAt.set(streamState, Instant.now().minusSeconds(secondsAgo));
        }

        private List<String> sqlStatements() {
            return mockingDetails(jdbc).getInvocations().stream()
                    .filter(invocation -> "update".equals(invocation.getMethod().getName()))
                    .map(invocation -> String.valueOf(flattenedArguments(invocation.getArguments())[0]))
                    .toList();
        }

        private List<String> closeReasons() {
            return mockingDetails(jdbc).getInvocations().stream()
                    .filter(invocation -> "update".equals(invocation.getMethod().getName()))
                    .map(invocation -> flattenedArguments(invocation.getArguments()))
                    .filter(arguments -> arguments.length >= 3 && String.valueOf(arguments[0]).contains("set status = ?, close_reason = ?"))
                    .map(arguments -> String.valueOf(arguments[2]))
                    .toList();
        }

        @SuppressWarnings("unchecked")
        private Object streamState() throws Exception {
            Field streamsField = AudioStreamWebSocketHandler.class.getDeclaredField("streams");
            streamsField.setAccessible(true);
            ConcurrentMap<String, Object> streams = (ConcurrentMap<String, Object>) streamsField.get(handler);
            return streams.values().stream().findFirst().orElse(null);
        }

        private Object[] flattenedArguments(Object[] arguments) {
            if (arguments.length == 2 && arguments[1] instanceof Object[] values) {
                Object[] flattened = new Object[values.length + 1];
                flattened[0] = arguments[0];
                System.arraycopy(values, 0, flattened, 1, values.length);
                return flattened;
            }
            return Arrays.copyOf(arguments, arguments.length);
        }
    }
}
