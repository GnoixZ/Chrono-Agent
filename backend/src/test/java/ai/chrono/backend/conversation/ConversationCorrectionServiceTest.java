package ai.chrono.backend.conversation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationCorrectionServiceTest {
    private JdbcTemplate jdbc;
    private ConversationCorrectionService service;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:conversation_correction_" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        dataSource.setDriverClassName("org.h2.Driver");
        jdbc = new JdbcTemplate(dataSource);
        service = new ConversationCorrectionService(jdbc);
        createSchema();
    }

    @Test
    void mergeCreatesDerivedConversationAndDiscardsSources() {
        UUID first = insertConversation("user-1", "会话 A", "先说了工作。");
        UUID second = insertConversation("user-1", "会话 B", "然后聊了休息。");

        var result = service.merge(new ConversationMergeRequest(
                "user-1",
                List.of(first.toString(), second.toString()),
                "close_gap"
        ));

        assertThat(result.get("sourceType")).isEqualTo("conversation_merge");
        assertThat(jdbc.queryForObject("select count(*) from conversation_memory where discarded = true", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action = 'conversation.merge'", Integer.class)).isEqualTo(1);
    }

    @Test
    void splitCreatesPartsAndKeepsSourceTraceable() {
        UUID source = insertConversation("user-1", "长会话", "前半段是工作，后半段是休息。");

        var result = service.split(source, new ConversationSplitRequest(
                "user-1",
                List.of(
                        new ConversationSplitRequest.Part("工作段", "前半段是工作。"),
                        new ConversationSplitRequest.Part("休息段", "后半段是休息。")
                ),
                "too_large"
        ));

        assertThat((List<?>) result.get("createdConversationMemoryIds")).hasSize(2);
        assertThat(jdbc.queryForObject("select discarded from conversation_memory where id = ?", Boolean.class, source)).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from conversation_memory where correction_of_id = ?", Integer.class, source)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action = 'conversation.split'", Integer.class)).isEqualTo(1);
    }

    @Test
    void reprocessIncrementsAttempts() {
        UUID source = insertConversation("user-1", "会话", "原摘要");

        var result = service.reprocess(source, new ConversationReprocessRequest("user-1", "manual"));

        assertThat(result.get("processingAttempts")).isEqualTo(2);
        assertThat(result.get("postProcessingStatus")).isEqualTo("completed");
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action = 'conversation.reprocess'", Integer.class)).isEqualTo(1);
    }

    private void createSchema() {
        jdbc.execute("create domain if not exists jsonb as json");
        jdbc.execute("""
                create table conversation_memory (
                    id uuid primary key,
                    user_id varchar(128) not null,
                    source_type varchar(64) not null,
                    source_audio_event_id uuid,
                    source_stream_session_id uuid,
                    correction_of_id uuid,
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
        jdbc.execute("""
                create table audio_event (
                    id uuid primary key,
                    stream_session_id uuid,
                    window_index integer
                )
                """);
        jdbc.execute("""
                create table speaker_cluster (
                    id uuid primary key,
                    display_name text
                )
                """);
        jdbc.execute("""
                create table speaker_segment (
                    id uuid primary key,
                    audio_event_id uuid,
                    speaker_cluster_id uuid,
                    start_ms integer,
                    transcript text
                )
                """);
    }

    private UUID insertConversation(String userId, String title, String overview) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.parse("2026-06-12T00:00:00Z");
        jdbc.update("""
                        insert into conversation_memory (
                            id, user_id, source_type, started_at, ended_at, title, overview, language, category,
                            status, post_processing_status, processing_attempts, discarded, visibility,
                            transcript_ref, created_at, updated_at
                        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                id, userId, "audio_session", Timestamp.from(now), Timestamp.from(now.plusSeconds(60)),
                title, overview, "zh", "life_log", "completed", "completed", 1, false,
                "private", "inline://test/" + id, Timestamp.from(now), Timestamp.from(now));
        return id;
    }
}
