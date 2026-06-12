package ai.chrono.backend.demo;

import ai.chrono.backend.audio.AudioStorage;
import ai.chrono.backend.health.HealthService;
import ai.chrono.backend.memory.MemoryCandidateDecisionService;
import ai.chrono.backend.modelclient.ModelServiceClient;
import ai.chrono.backend.modelclient.dto.AnalyzeAudioRequest;
import ai.chrono.backend.modelclient.dto.AnalyzeAudioResponse;
import ai.chrono.backend.speaker.PersonInsightService;
import ai.chrono.backend.task.ModelJobService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DemoPipelineServiceTest {
    private JdbcTemplate jdbc;
    private AudioStorage audioStorage;
    private ModelServiceClient modelServiceClient;
    private DemoPipelineService service;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:demo_pipeline_" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        dataSource.setDriverClassName("org.h2.Driver");
        jdbc = new JdbcTemplate(dataSource);
        audioStorage = mock(AudioStorage.class);
        modelServiceClient = mock(ModelServiceClient.class);
        service = new DemoPipelineService(
                jdbc,
                audioStorage,
                modelServiceClient,
                mock(ModelJobService.class),
                mock(HealthService.class),
                new MemoryCandidateDecisionService(),
                new PersonInsightService()
        );
        createSchema();
    }

    @Test
    void processStreamWindowUsesAudioBytesAndMarksAllSpeakersAsSelfWithoutVoiceprint() {
        UUID streamSessionId = UUID.randomUUID();
        UUID audioEventId = UUID.randomUUID();
        UUID modelJobId = UUID.randomUUID();
        Instant now = Instant.parse("2026-06-12T03:00:00Z");
        insertStreamSession(streamSessionId, "user-1", now);
        insertAudioEvent(audioEventId, streamSessionId, "user-1", "local://user-1/window.webm", now);
        insertModelJob(modelJobId, "user-1", audioEventId, now);
        when(audioStorage.read("local://user-1/window.webm")).thenReturn("voice".getBytes());
        when(modelServiceClient.analyzeAudio(any(AnalyzeAudioRequest.class))).thenReturn(audioResponseWithSpeakerEmbeddings());

        DemoAudioResult result = service.processPendingAudio(audioEventId);

        assertThat(result.processingStatus()).isEqualTo("completed");
        assertThat(jdbc.queryForObject("select count(*) from speaker_cluster where display_name = '本人' and status = 'known' and user_labeled = true", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForList("select is_user from speaker_segment where audio_event_id = ? order by start_ms", Boolean.class, audioEventId))
                .containsExactly(true, true);
        assertThat(jdbc.queryForObject("select count(*) from speaker_embedding", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from speaker_label_suggestion", Integer.class)).isZero();

        ArgumentCaptor<AnalyzeAudioRequest> requestCaptor = ArgumentCaptor.forClass(AnalyzeAudioRequest.class);
        verify(modelServiceClient).analyzeAudio(requestCaptor.capture());
        AnalyzeAudioRequest request = requestCaptor.getValue();
        assertThat(request.audioContentBase64()).isEqualTo("dm9pY2U=");
        assertThat(request.audioFormat()).isEqualTo("webm");
    }

    @Test
    void processStreamSessionCreatesConversationMemoryAndAutoSavedMemoryItem() {
        UUID streamSessionId = UUID.randomUUID();
        UUID audioEventId = UUID.randomUUID();
        UUID modelJobId = UUID.randomUUID();
        Instant now = Instant.parse("2026-06-12T04:00:00Z");
        insertStreamSession(streamSessionId, "user-1", now);
        insertAudioEvent(audioEventId, streamSessionId, "user-1", "local://user-1/session.webm", now);
        insertModelJob(modelJobId, "user-1", audioEventId, now);
        when(audioStorage.read("local://user-1/session.webm")).thenReturn("voice".getBytes());
        when(modelServiceClient.analyzeAudio(any(AnalyzeAudioRequest.class))).thenReturn(audioResponseWithSpeakerEmbeddings());

        service.processPendingAudio(audioEventId);
        DemoAudioResult result = service.processStreamSession(streamSessionId, "silence_timeout_60s");

        assertThat(result.conversationMemoryId()).isNotBlank();
        assertThat(jdbc.queryForObject("select count(*) from conversation_memory where source_stream_session_id = ? and status = 'completed'", Integer.class, streamSessionId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from memory_write_candidate where conversation_memory_id = ? and decision = 'auto_saved'", Integer.class, UUID.fromString(result.conversationMemoryId())))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from memory_item where user_id = ? and source_type = ? and content like ?", Integer.class,
                "user-1", "session_post_processing", "用户最近的流式语音会话内容：%"))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select session_conversation_memory_id from audio_stream_session where id = ?", UUID.class, streamSessionId))
                .isEqualTo(UUID.fromString(result.conversationMemoryId()));
    }

    private AnalyzeAudioResponse audioResponseWithSpeakerEmbeddings() {
        return new AnalyzeAudioResponse(
                "zh",
                List.of(
                        new AnalyzeAudioResponse.SpeakerSegmentDto(7, 0, 900, "第一段。", 0.88, List.of("calm"), List.of("demo")),
                        new AnalyzeAudioResponse.SpeakerSegmentDto(8, 1000, 1800, "第二段。", 0.86, List.of("calm"), List.of("demo"))
                ),
                List.of(
                        new AnalyzeAudioResponse.SpeakerEmbeddingDto(7, "voiceprint://7", 0.91),
                        new AnalyzeAudioResponse.SpeakerEmbeddingDto(8, "voiceprint://8", 0.9)
                ),
                new AnalyzeAudioResponse.ConversationSummaryDto(
                        "窗口转写",
                        "用户说了两段话。",
                        List.of("demo"),
                        List.of("calm"),
                        List.of(),
                        List.of(),
                        false,
                        null
                ),
                List.of(),
                new AnalyzeAudioResponse.SafetyResultDto("normal", false, null)
        );
    }

    private void createSchema() {
        jdbc.execute("create domain if not exists jsonb as json");
        jdbc.execute("""
                create table audio_stream_session (
                    id uuid primary key,
                    user_id varchar(128) not null,
                    started_at timestamp with time zone,
                    session_started_at timestamp with time zone,
                    closed_at timestamp with time zone,
                    status varchar(32) not null,
                    session_status varchar(32),
                    session_conversation_memory_id uuid,
                    session_post_processing_status varchar(32),
                    updated_at timestamp with time zone
                )
                """);
        jdbc.execute("""
                create table audio_event (
                    id uuid primary key,
                    user_id varchar(128) not null,
                    audio_uri text not null,
                    started_at timestamp with time zone not null,
                    ended_at timestamp with time zone,
                    stream_session_id uuid,
                    window_index integer,
                    codec varchar(32),
                    processing_status varchar(32) not null,
                    created_at timestamp with time zone,
                    updated_at timestamp with time zone
                )
                """);
        jdbc.execute("""
                create table model_job (
                    id uuid primary key,
                    user_id varchar(128) not null,
                    source_ref_type varchar(64) not null,
                    source_ref_id uuid not null,
                    status varchar(32) not null,
                    attempts integer not null,
                    next_run_at timestamp with time zone,
                    response_ref text,
                    created_at timestamp with time zone not null,
                    updated_at timestamp with time zone
                )
                """);
        jdbc.execute("""
                create table speaker_cluster (
                    id uuid primary key,
                    user_id varchar(128) not null,
                    display_name varchar(255) not null,
                    status varchar(32) not null,
                    created_from varchar(64) not null,
                    first_seen_at timestamp with time zone not null,
                    last_seen_at timestamp with time zone not null,
                    match_confidence_summary jsonb not null default '{}',
                    user_labeled boolean not null default false,
                    label_suggestion varchar(255),
                    deleted_at timestamp with time zone,
                    created_at timestamp with time zone not null,
                    updated_at timestamp with time zone not null
                )
                """);
        jdbc.execute("""
                create table speaker_segment (
                    id uuid primary key,
                    audio_event_id uuid not null,
                    speaker_cluster_id uuid,
                    speaker_id integer not null,
                    is_user boolean not null,
                    start_ms integer not null,
                    end_ms integer not null,
                    transcript text not null,
                    language varchar(16),
                    confidence double precision not null,
                    emotion_tags jsonb not null default '[]',
                    topic_tags jsonb not null default '[]',
                    created_at timestamp with time zone not null
                )
                """);
        jdbc.execute("""
                create table speaker_embedding (
                    id uuid primary key,
                    speaker_cluster_id uuid not null,
                    audio_event_id uuid,
                    embedding_ref text not null
                )
                """);
        jdbc.execute("""
                create table speaker_label_suggestion (
                    id uuid primary key,
                    speaker_cluster_id uuid not null,
                    suggested_label varchar(255) not null
                )
                """);
        jdbc.execute("""
                create table conversation_memory (
                    id uuid primary key,
                    user_id varchar(128) not null,
                    source_type varchar(64) not null,
                    source_audio_event_id uuid,
                    source_stream_session_id uuid,
                    started_at timestamp with time zone,
                    ended_at timestamp with time zone,
                    title text not null,
                    overview text not null,
                    language varchar(16),
                    category varchar(64),
                    status varchar(32) not null,
                    post_processing_status varchar(32) not null,
                    processing_attempts integer not null default 0,
                    discarded boolean not null default false,
                    discard_reason text,
                    visibility varchar(32) not null default 'private',
                    transcript_ref text,
                    speaker_refs jsonb not null default '[]',
                    health_refs jsonb not null default '[]',
                    topic_tags jsonb not null default '[]',
                    emotion_tags jsonb not null default '[]',
                    suggested_actions jsonb not null default '[]',
                    suggested_events jsonb not null default '[]',
                    evidence_refs jsonb not null default '[]',
                    created_at timestamp with time zone not null,
                    updated_at timestamp with time zone not null,
                    deleted_at timestamp with time zone
                )
                """);
        jdbc.execute("""
                create table memory_write_candidate (
                    id uuid primary key,
                    conversation_session_id uuid,
                    conversation_memory_id uuid,
                    source_message_id uuid,
                    source_type varchar(64) not null,
                    memory_type varchar(64) not null,
                    content text not null,
                    confidence double precision not null,
                    decision varchar(64) not null,
                    decision_reason text,
                    created_at timestamp with time zone not null,
                    decided_at timestamp with time zone
                )
                """);
        jdbc.execute("""
                create table memory_item (
                    id uuid primary key,
                    user_id varchar(128) not null,
                    source_type varchar(64) not null,
                    memory_type varchar(64) not null,
                    scope varchar(64) not null,
                    subject_type varchar(64),
                    subject_id uuid,
                    content text not null,
                    confidence double precision not null,
                    source varchar(64) not null,
                    evidence_refs jsonb not null default '[]',
                    created_at timestamp with time zone not null,
                    updated_at timestamp with time zone not null,
                    valid_at timestamp with time zone not null
                )
                """);
        jdbc.execute("""
                create table person_insight (
                    id uuid primary key,
                    user_id varchar(128) not null,
                    speaker_cluster_id uuid not null,
                    insight_type varchar(64) not null,
                    time_window_start timestamp with time zone not null,
                    time_window_end timestamp with time zone not null,
                    summary text not null,
                    evidence_refs jsonb not null default '[]',
                    confidence double precision not null,
                    safety_level varchar(64) not null,
                    created_at timestamp with time zone not null
                )
                """);
        jdbc.execute("""
                create table audit_log (
                    id uuid primary key,
                    user_id varchar(128) not null,
                    actor_type varchar(64) not null,
                    action varchar(128) not null,
                    target_type varchar(64) not null,
                    target_id uuid,
                    metadata jsonb not null default '{}',
                    created_at timestamp with time zone not null
                )
                """);
    }

    private void insertStreamSession(UUID streamSessionId, String userId, Instant now) {
        jdbc.update("""
                        insert into audio_stream_session (
                            id, user_id, started_at, session_started_at, closed_at, status, session_status, updated_at
                        ) values (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                streamSessionId, userId, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now.plusSeconds(2)),
                "active", "in_session", Timestamp.from(now));
    }

    private void insertAudioEvent(UUID audioEventId, UUID streamSessionId, String userId, String audioUri, Instant now) {
        jdbc.update("""
                        insert into audio_event (
                            id, user_id, audio_uri, started_at, ended_at, stream_session_id, window_index, codec,
                            processing_status, created_at, updated_at
                        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                audioEventId, userId, audioUri, Timestamp.from(now), Timestamp.from(now.plusSeconds(2)),
                streamSessionId, 1, "webm", "processing", Timestamp.from(now), Timestamp.from(now));
    }

    private void insertModelJob(UUID modelJobId, String userId, UUID audioEventId, Instant now) {
        jdbc.update("""
                        insert into model_job (
                            id, user_id, source_ref_type, source_ref_id, status, attempts, next_run_at, created_at, updated_at
                        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                modelJobId, userId, "audio_event", audioEventId, "pending", 0, Timestamp.from(now),
                Timestamp.from(now), Timestamp.from(now));
    }
}
