package ai.chrono.backend.demo;

import ai.chrono.backend.audio.AudioStorage;
import ai.chrono.backend.health.HealthEventRequest;
import ai.chrono.backend.health.HealthEventResponse;
import ai.chrono.backend.health.HealthEventTypes;
import ai.chrono.backend.health.HealthService;
import ai.chrono.backend.memory.MemoryCandidateDecisionService;
import ai.chrono.backend.modelclient.ModelServiceClient;
import ai.chrono.backend.modelclient.dto.AgentReplyRequest;
import ai.chrono.backend.modelclient.dto.AgentReplyResponse;
import ai.chrono.backend.modelclient.dto.AnalyzeAudioRequest;
import ai.chrono.backend.modelclient.dto.AnalyzeAudioResponse;
import ai.chrono.backend.modelclient.dto.VectorDocumentDto;
import ai.chrono.backend.modelclient.dto.VectorSearchRequest;
import ai.chrono.backend.modelclient.dto.VectorSearchResponse;
import ai.chrono.backend.modelclient.dto.VectorUpsertRequest;
import ai.chrono.backend.speaker.PersonInsightService;
import ai.chrono.backend.task.ModelJobService;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.sql.ResultSetMetaData;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class DemoPipelineService {
    private static final String JSONB = "cast(? as jsonb)";

    public record AudioWindowMetadata(
            Integer windowIndex,
            Instant windowStartedAt,
            Instant windowEndedAt,
            boolean finalWindow
    ) {
    }

    public record PendingAudioAnalysis(
            String audioEventId,
            String modelJobId,
            String processingStatus
    ) {
    }

    private final JdbcTemplate jdbc;
    private final AudioStorage audioStorage;
    private final ModelServiceClient modelServiceClient;
    private final ModelJobService modelJobService;
    private final HealthService healthService;
    private final MemoryCandidateDecisionService memoryDecisionService;
    private final PersonInsightService personInsightService;

    public DemoPipelineService(
            JdbcTemplate jdbc,
            AudioStorage audioStorage,
            ModelServiceClient modelServiceClient,
            ModelJobService modelJobService,
            HealthService healthService,
            MemoryCandidateDecisionService memoryDecisionService,
            PersonInsightService personInsightService
    ) {
        this.jdbc = jdbc;
        this.audioStorage = audioStorage;
        this.modelServiceClient = modelServiceClient;
        this.modelJobService = modelJobService;
        this.healthService = healthService;
        this.memoryDecisionService = memoryDecisionService;
        this.personInsightService = personInsightService;
    }

    @Transactional
    public DemoAudioResult processAudio(String userId, String fileName, byte[] bytes, String sourceType, UUID streamSessionId) {
        return processAudio(userId, fileName, bytes, sourceType, streamSessionId, null);
    }

    @Transactional
    public DemoAudioResult processAudio(String userId, String fileName, byte[] bytes, String sourceType, UUID streamSessionId, AudioWindowMetadata windowMetadata) {
        PendingAudioAnalysis pendingAudioAnalysis = enqueueAudioAnalysis(userId, fileName, bytes, sourceType, streamSessionId, windowMetadata);
        return processPendingAudio(UUID.fromString(pendingAudioAnalysis.audioEventId()));
    }

    @Transactional
    public PendingAudioAnalysis enqueueAudioAnalysis(String userId, String fileName, byte[] bytes, String sourceType, UUID streamSessionId, AudioWindowMetadata windowMetadata) {
        Instant now = Instant.now();
        AudioStorage.StoredAudio storedAudio = audioStorage.save(new AudioStorage.AudioInput(userId, safeFileName(fileName), bytes));
        UUID audioEventId = UUID.randomUUID();
        ModelJobService.ModelJobDraft modelJobDraft = modelJobService.createAudioAnalyzeJob(userId, audioEventId);

        jdbc.update("""
                        insert into audio_event (
                            id, user_id, source_type, started_at, ended_at, audio_uri, processing_status,
                            stream_session_id, sample_rate, codec, duration_ms, retention_expires_at,
                            window_index, window_started_at, window_ended_at, is_final_window,
                            created_at, updated_at
                        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                audioEventId, userId, sourceType,
                windowTimestamp(windowMetadata == null ? null : windowMetadata.windowStartedAt(), now),
                windowTimestamp(windowMetadata == null ? null : windowMetadata.windowEndedAt(), now),
                storedAudio.audioUri(), "processing",
                streamSessionId, 16000, fileNameCodec(fileName), null, Timestamp.from(now.plus(30, ChronoUnit.DAYS)),
                windowMetadata == null ? null : windowMetadata.windowIndex(),
                windowTimestamp(windowMetadata == null ? null : windowMetadata.windowStartedAt(), null),
                windowTimestamp(windowMetadata == null ? null : windowMetadata.windowEndedAt(), null),
                windowMetadata != null && windowMetadata.finalWindow(),
                Timestamp.from(now), Timestamp.from(now));

        jdbc.update("""
                        insert into model_job (
                            id, user_id, job_type, source_ref_type, source_ref_id, status, attempts, next_run_at,
                            request_ref, response_ref, idempotency_key, created_at, updated_at
                        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                modelJobDraft.id(), modelJobDraft.userId(), modelJobDraft.jobType(), modelJobDraft.sourceRefType(), modelJobDraft.sourceRefId(),
                modelJobDraft.status(), modelJobDraft.attempts(), Timestamp.from(modelJobDraft.nextRunAt()),
                "audio://" + audioEventId, null, modelJobDraft.idempotencyKey(), Timestamp.from(now), Timestamp.from(now));

        return new PendingAudioAnalysis(audioEventId.toString(), modelJobDraft.id().toString(), "processing");
    }

    @Transactional
    public DemoAudioResult processPendingAudio(UUID audioEventId) {
        Map<String, Object> audioEvent = jdbc.queryForMap("""
                select user_id, audio_uri, started_at, ended_at, stream_session_id, window_index, codec
                from audio_event
                where id = ?
                """, audioEventId);
        String userId = String.valueOf(audioEvent.get("user_id"));
        String audioUri = String.valueOf(audioEvent.get("audio_uri"));
        Instant startedAt = timestampValue(audioEvent.get("started_at"), Instant.now());
        Instant endedAt = timestampValue(audioEvent.get("ended_at"), startedAt);
        UUID streamSessionId = parseUuid(String.valueOf(audioEvent.get("stream_session_id")));
        byte[] audioBytes = audioStorage.read(audioUri);
        String audioContentBase64 = Base64.getEncoder().encodeToString(audioBytes);
        Object codecValue = audioEvent.get("codec");
        String audioFormat = codecValue == null ? "webm" : blankToDefault(String.valueOf(codecValue), "webm");
        UUID modelJobId = jdbc.queryForObject("""
                select id
                from model_job
                where source_ref_type = ? and source_ref_id = ?
                order by created_at desc
                limit 1
                """, UUID.class, "audio_event", audioEventId);

        jdbc.update("""
                        update model_job
                        set status = ?, attempts = attempts + 1, next_run_at = ?, updated_at = ?
                        where id = ?
                        """,
                "running", Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), modelJobId);

        AnalyzeAudioResponse response;
        try {
            response = modelServiceClient.analyzeAudio(new AnalyzeAudioRequest(
                    UUID.randomUUID().toString(),
                    userId,
                    audioEventId.toString(),
                    audioUri,
                    audioContentBase64,
                    audioFormat,
                    startedAt.toString(),
                    endedAt.toString(),
                    List.of()
            ));
        } catch (RuntimeException error) {
            jdbc.update("""
                            update model_job
                            set status = ?, last_error_type = ?, last_error_message = ?, updated_at = ?
                            where id = ?
                            """,
                    "failed", error.getClass().getSimpleName(), trim(error.getMessage(), 500), Timestamp.from(Instant.now()), modelJobId);
            jdbc.update("update audio_event set processing_status = ?, updated_at = ? where id = ?", "failed", Timestamp.from(Instant.now()), audioEventId);
            throw error;
        }

        boolean discarded = Boolean.TRUE.equals(response.summary().discard());
        String processingStatus = discarded ? "discarded" : "completed";
        Instant now = Instant.now();
        List<Map<String, Object>> speakerRefs = persistSpeakerData(userId, audioEventId, response, now);

        if (streamSessionId != null) {
            jdbc.update("update model_job set status = ?, response_ref = ?, updated_at = ? where id = ?",
                    "completed", "audio_window://" + audioEventId, Timestamp.from(now), modelJobId);
            jdbc.update("update audio_event set processing_status = ?, updated_at = ? where id = ?",
                    processingStatus, Timestamp.from(now), audioEventId);
            audit(userId, "audio.window_processed", "audio_event", audioEventId, Map.of(
                    "streamSessionId", streamSessionId.toString(),
                    "windowIndex", String.valueOf(audioEvent.get("window_index"))
            ));
            return new DemoAudioResult(
                    audioEventId.toString(),
                    modelJobId.toString(),
                    null,
                    processingStatus,
                    response.summary().title(),
                    response.summary().overview(),
                    rows("select id, speaker_id, speaker_cluster_id, start_ms, end_ms, transcript, confidence, emotion_tags, topic_tags from speaker_segment where audio_event_id = ? order by start_ms", audioEventId),
                    rows("select id, display_name, status, user_labeled, first_seen_at, last_seen_at, label_suggestion from speaker_cluster where user_id = ? and deleted_at is null order by last_seen_at desc", userId),
                    List.of()
            );
        }

        UUID conversationMemoryId = UUID.randomUUID();

        jdbc.update("""
                        insert into conversation_memory (
                            id, user_id, source_type, source_audio_event_id, started_at, ended_at, title, overview,
                            language, category, status, post_processing_status, processing_attempts, discarded, discard_reason,
                            visibility, transcript_ref, speaker_refs, health_refs, topic_tags, emotion_tags,
                            suggested_actions, suggested_events, created_at, updated_at
                        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, %s, %s, %s, %s, %s, %s, ?, ?)
                        """.formatted(JSONB, JSONB, JSONB, JSONB, JSONB, JSONB),
                conversationMemoryId, userId, "audio", audioEventId, Timestamp.from(startedAt), Timestamp.from(endedAt),
                response.summary().title(), response.summary().overview(), response.language(), "life_log",
                processingStatus, "completed", 1, discarded, response.summary().discardReason(), "private",
                "inline://speaker-segments/" + audioEventId,
                json(speakerRefs), json(List.of()), json(nullSafe(response.summary().topicTags())), json(nullSafe(response.summary().emotionTags())),
                json(nullSafe(response.summary().suggestedActions())), json(nullSafe(response.summary().suggestedEvents())),
                Timestamp.from(now), Timestamp.from(now));

        persistMemoryCandidates(response.memoryCandidates(), null, conversationMemoryId, null, "model_suggested");
        persistPersonInsights(userId, speakerRefs, now);
        tryIndexVectorDocument(
                userId,
                "conversation_memory",
                conversationMemoryId,
                response.summary().title() + "\n" + response.summary().overview(),
                "录音分析形成的会话记录",
                now
        );

        jdbc.update("update model_job set status = ?, response_ref = ?, updated_at = ? where id = ?",
                "completed", "conversation_memory://" + conversationMemoryId, Timestamp.from(Instant.now()), modelJobId);
        jdbc.update("update audio_event set processing_status = ?, updated_at = ? where id = ?",
                processingStatus, Timestamp.from(Instant.now()), audioEventId);
        audit(userId, "audio.processed", "audio_event", audioEventId, Map.of("conversationMemoryId", conversationMemoryId.toString()));

        return new DemoAudioResult(
                audioEventId.toString(),
                modelJobId.toString(),
                conversationMemoryId.toString(),
                processingStatus,
                response.summary().title(),
                response.summary().overview(),
                rows("select id, speaker_id, speaker_cluster_id, start_ms, end_ms, transcript, confidence, emotion_tags, topic_tags from speaker_segment where audio_event_id = ? order by start_ms", audioEventId),
                rows("select id, display_name, status, user_labeled, first_seen_at, last_seen_at, label_suggestion from speaker_cluster where user_id = ? and deleted_at is null order by last_seen_at desc", userId),
                rows("select id, memory_type, content, confidence, decision, created_at from memory_write_candidate where conversation_memory_id = ? order by created_at desc", conversationMemoryId)
        );
    }

    @Transactional
    public DemoAudioResult processStreamSession(UUID streamSessionId, String closeReason) {
        Map<String, Object> session = jdbc.queryForMap("""
                select user_id, started_at, session_started_at, closed_at
                from audio_stream_session
                where id = ?
                """, streamSessionId);
        String userId = String.valueOf(session.get("user_id"));
        Instant now = Instant.now();
        Instant startedAt = timestampValue(session.get("session_started_at"), timestampValue(session.get("started_at"), now));
        Instant endedAt = timestampValue(session.get("closed_at"), now);
        List<Map<String, Object>> audioEvents = rows("""
                select id, window_index, processing_status, started_at, ended_at
                from audio_event
                where stream_session_id = ?
                order by window_index nulls last, created_at
                """, streamSessionId);
        if (audioEvents.isEmpty()) {
            throw new IllegalStateException("stream session has no audio windows");
        }
        List<Map<String, Object>> segments = rows("""
                select ss.id, ss.audio_event_id, ss.speaker_cluster_id, sc.display_name, ss.speaker_id,
                       ss.start_ms, ss.end_ms, ss.transcript, ss.confidence, ss.emotion_tags, ss.topic_tags
                from speaker_segment ss
                join audio_event ae on ae.id = ss.audio_event_id
                left join speaker_cluster sc on sc.id = ss.speaker_cluster_id
                where ae.stream_session_id = ?
                order by ae.window_index nulls last, ss.start_ms
                """, streamSessionId);
        String transcript = combinedTranscript(segments);
        boolean discarded = transcript.isBlank();
        String title = discarded ? "低价值流式会话" : "流式会话复盘";
        String overview = discarded
                ? "本次流式会话没有形成可用转写。"
                : summarizeTranscript(transcript);
        UUID conversationMemoryId = UUID.randomUUID();
        UUID sourceAudioEventId = parseUuid(String.valueOf(audioEvents.get(0).get("id")));
        List<Map<String, Object>> speakerRefs = segments.stream()
                .map(segment -> linkedMap(
                        "speakerClusterId", segment.get("speakerClusterId"),
                        "speakerSegmentId", segment.get("id"),
                        "audioEventId", segment.get("audioEventId"),
                        "speakerId", segment.get("speakerId"),
                        "startMs", segment.get("startMs"),
                        "endMs", segment.get("endMs")
                ))
                .toList();
        List<Map<String, Object>> evidenceRefs = audioEvents.stream()
                .map(event -> linkedMap(
                        "type", "audio_event",
                        "id", event.get("id"),
                        "windowIndex", event.get("windowIndex"),
                        "processingStatus", event.get("processingStatus")
                ))
                .toList();

        jdbc.update("""
                        insert into conversation_memory (
                            id, user_id, source_type, source_audio_event_id, source_stream_session_id, started_at, ended_at,
                            title, overview, language, category, status, post_processing_status, processing_attempts,
                            discarded, discard_reason, visibility, transcript_ref, speaker_refs, health_refs, topic_tags,
                            emotion_tags, suggested_actions, suggested_events, evidence_refs, created_at, updated_at
                        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, %s, %s, %s, %s, %s, %s, %s, ?, ?)
                        """.formatted(JSONB, JSONB, JSONB, JSONB, JSONB, JSONB, JSONB),
                conversationMemoryId, userId, "audio_session", sourceAudioEventId, streamSessionId,
                Timestamp.from(startedAt), Timestamp.from(endedAt), title, overview, "zh", "life_log",
                discarded ? "discarded" : "completed", "completed", 1, discarded,
                discarded ? "blank_or_low_value_stream_session" : null, "private",
                "inline://audio-stream-session/" + streamSessionId + "/transcript",
                json(speakerRefs), json(List.of()), json(List.of("stream_session")), json(List.of()),
                json(List.of()), json(List.of()), json(evidenceRefs), Timestamp.from(now), Timestamp.from(now));

        if (!discarded) {
            persistMemoryCandidates(List.of(new AnalyzeAudioResponse.MemoryCandidateDto(
                    "conversation_context",
                    "用户完成了一次流式语音会话：" + trim(overview, 160),
                    0.58,
                    "normal"
            )), null, conversationMemoryId, null, "session_post_processing");
            persistPersonInsights(userId, speakerRefs, now);
            tryIndexVectorDocument(userId, "conversation_memory", conversationMemoryId, title + "\n" + overview, "流式会话后处理形成的会话记录", now);
        }

        jdbc.update("""
                        update audio_stream_session
                        set session_conversation_memory_id = ?, session_post_processing_status = ?, session_status = ?, updated_at = ?
                        where id = ?
                        """,
                conversationMemoryId, "completed", "closed", Timestamp.from(now), streamSessionId);
        audit(userId, "audio_stream_session.post_processed", "audio_stream_session", streamSessionId, Map.of(
                "conversationMemoryId", conversationMemoryId.toString(),
                "closeReason", closeReason == null ? "" : closeReason,
                "windowCount", audioEvents.size()
        ));

        return new DemoAudioResult(
                sourceAudioEventId == null ? null : sourceAudioEventId.toString(),
                null,
                conversationMemoryId.toString(),
                discarded ? "discarded" : "completed",
                title,
                overview,
                segments,
                rows("select id, display_name, status, user_labeled, first_seen_at, last_seen_at, label_suggestion from speaker_cluster where user_id = ? and deleted_at is null order by last_seen_at desc", userId),
                rows("select id, memory_type, content, confidence, decision, created_at from memory_write_candidate where conversation_memory_id = ? order by created_at desc", conversationMemoryId)
        );
    }

    @Transactional(readOnly = true)
    public DemoStateResponse getState(String userId) {
        return new DemoStateResponse(
                userId,
                rows("""
                        select id, source_type, started_at, ended_at, audio_uri, processing_status, stream_session_id,
                               window_index, window_started_at, window_ended_at, is_final_window, created_at
                        from audio_event
                        where user_id = ?
                        order by created_at desc limit 20
                        """, userId),
                rows("""
                        select id, source_type, status, session_status, started_at, session_started_at, session_ended_at,
                               last_active_at, closed_at, close_reason, session_close_reason, session_conversation_memory_id,
                               session_post_processing_status, current_audio_event_id, window_count, processed_window_count,
                               failed_window_count, created_at, updated_at
                        from audio_stream_session
                        where user_id = ?
                        order by created_at desc limit 20
                        """, userId),
                rows("select id, event_type, measured_at, value_numeric, value_text, unit, source, created_at from health_event where user_id = ? order by measured_at desc limit 30", userId),
                rows("select id, source_type, source_audio_event_id, source_stream_session_id, started_at, title, overview, status, discarded, discard_reason, topic_tags, emotion_tags, evidence_refs, created_at from conversation_memory where user_id = ? and deleted_at is null order by created_at desc limit 20", userId),
                rows("select id, display_name, status, user_labeled, label_suggestion, first_seen_at, last_seen_at, match_confidence_summary from speaker_cluster where user_id = ? and deleted_at is null order by last_seen_at desc limit 20", userId),
                rows("""
                        select ss.id, ss.audio_event_id, ss.speaker_cluster_id, sc.display_name, ss.start_ms, ss.end_ms, ss.transcript, ss.confidence, ss.emotion_tags, ss.topic_tags
                        from speaker_segment ss
                        join audio_event ae on ae.id = ss.audio_event_id
                        left join speaker_cluster sc on sc.id = ss.speaker_cluster_id
                        where ae.user_id = ?
                        order by ss.created_at desc limit 30
                        """, userId),
                rows("select id, speaker_cluster_id, insight_type, summary, confidence, safety_level, created_at from person_insight where user_id = ? order by created_at desc limit 20", userId),
                rows("""
                        select mwc.id, mwc.conversation_memory_id, mwc.conversation_session_id, mwc.source_message_id,
                               mwc.source_type, mwc.memory_type, mwc.content, mwc.confidence, mwc.decision, mwc.created_at, mwc.decided_at
                        from memory_write_candidate mwc
                        left join conversation_memory cm on cm.id = mwc.conversation_memory_id
                        left join conversation_session cs on cs.id = mwc.conversation_session_id
                        left join agent_message am on am.id = mwc.source_message_id
                        where cm.user_id = ? or cs.user_id = ? or am.user_id = ?
                        order by mwc.created_at desc limit 30
                        """, userId, userId, userId),
                rows("select id, memory_type, scope, subject_type, subject_id, content, confidence, source, evidence_refs, created_at, last_used_at from memory_item where user_id = ? and invalid_at is null and deleted_at is null order by created_at desc limit 30", userId),
                rows("select id, title, session_type, started_at, last_message_at, status, source from conversation_session where user_id = ? order by last_message_at desc limit 20", userId),
                rows("select id, conversation_session_id, role, content_type, content, safety_level, created_at from agent_message where user_id = ? and deleted_at is null order by created_at desc limit 40", userId),
                rows("""
                        select ar.id, ar.conversation_session_id, ar.trigger_message_id, ar.status, ar.short_term_memory_ref,
                               ar.retrieved_context_ref, ar.safety_result, ar.created_at, ar.completed_at
                        from agent_run ar
                        join conversation_session cs on cs.id = ar.conversation_session_id
                        where cs.user_id = ?
                        order by ar.created_at desc limit 20
                        """, userId),
                rows("""
                        select mre.id, mre.agent_run_id, mre.recall_type, mre.memory_item_id, mre.conversation_memory_id,
                               mre.rank, mre.reason, mre.score, mre.created_at
                        from memory_recall_event mre
                        join agent_run ar on ar.id = mre.agent_run_id
                        join conversation_session cs on cs.id = ar.conversation_session_id
                        where cs.user_id = ?
                        order by mre.created_at desc limit 50
                        """, userId),
                rows("select id, job_type, source_ref_type, source_ref_id, status, attempts, last_error_type, last_error_message, created_at, updated_at from model_job where user_id = ? order by created_at desc limit 20", userId),
                rows("select id, actor_type, action, target_type, target_id, metadata, created_at from audit_log where user_id = ? order by created_at desc limit 30", userId)
        );
    }

    @Transactional
    public Map<String, Object> createHealthEvent(DemoHealthRequest request) {
        if (!HealthEventTypes.isAllowed(request.eventType())) {
            throw new IllegalArgumentException("unsupported health event type");
        }
        HealthEventResponse response = healthService.create(new HealthEventRequest(
                request.userId(),
                request.eventType(),
                request.measuredAt(),
                request.valueNumeric(),
                request.valueText(),
                request.unit(),
                request.source()
        ));
        UUID healthEventId = UUID.fromString(response.id());
        audit(request.userId(), "health_event.created", "health_event", healthEventId, Map.of("eventType", request.eventType()));
        return linkedMap(
                "id", response.id(),
                "userId", response.userId(),
                "eventType", response.eventType(),
                "measuredAt", response.measuredAt(),
                "valueNumeric", response.valueNumeric(),
                "valueText", response.valueText(),
                "unit", response.unit(),
                "source", response.source(),
                "displayValue", response.displayValue(),
                "createdAt", response.createdAt()
        );
    }

    @Transactional
    public Map<String, Object> labelSpeaker(String userId, UUID speakerClusterId, String displayName) {
        Map<String, Object> before = row("select id, display_name, status from speaker_cluster where id = ? and user_id = ?", speakerClusterId, userId);
        if (before.isEmpty()) {
            throw new IllegalArgumentException("speaker cluster not found");
        }
        Instant now = Instant.now();
        jdbc.update("""
                        update speaker_cluster
                        set display_name = ?, status = ?, user_labeled = ?, label_suggestion = null, updated_at = ?
                        where id = ? and user_id = ?
                        """,
                displayName, "labeled", true, Timestamp.from(now), speakerClusterId, userId);
        jdbc.update("""
                        insert into person_label_history (id, speaker_cluster_id, user_id, action, old_value, new_value, reason, created_at)
                        values (?, ?, ?, ?, %s, %s, ?, ?)
                        """.formatted(JSONB, JSONB),
                UUID.randomUUID(), speakerClusterId, userId, "label", json(before), json(Map.of("displayName", displayName)),
                "demo_user_label", Timestamp.from(now));
        audit(userId, "speaker.label", "speaker_cluster", speakerClusterId, Map.of("displayName", displayName));
        return row("select id, display_name, status, user_labeled, first_seen_at, last_seen_at from speaker_cluster where id = ?", speakerClusterId);
    }

    @Transactional
    public Map<String, Object> acceptMemoryCandidate(UUID candidateId) {
        Map<String, Object> candidate = row("""
                        select mwc.*, coalesce(cm.user_id, cs.user_id, am.user_id) as user_id
                        from memory_write_candidate mwc
                        left join conversation_memory cm on cm.id = mwc.conversation_memory_id
                        left join conversation_session cs on cs.id = mwc.conversation_session_id
                        left join agent_message am on am.id = mwc.source_message_id
                        where mwc.id = ?
                        """, candidateId);
        if (candidate.isEmpty()) {
            throw new IllegalArgumentException("memory candidate not found");
        }
        String userId = String.valueOf(candidate.get("userId"));
        UUID memoryId = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                        insert into memory_item (
                            id, user_id, source_type, memory_type, scope, subject_type, subject_id, content, confidence, source,
                            evidence_refs, created_at, updated_at, valid_at
                        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, %s, ?, ?, ?)
                        """.formatted(JSONB),
                memoryId, userId, "user_confirmed", String.valueOf(candidate.get("memoryType")), "personal",
                null, null, String.valueOf(candidate.get("content")), number(candidate.get("confidence"), 0.8), "user_confirmed",
                json(List.of(Map.of("type", "memory_write_candidate", "id", candidateId.toString()))),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        jdbc.update("update memory_write_candidate set decision = ?, decided_at = ? where id = ?", "accepted", Timestamp.from(now), candidateId);
        tryIndexVectorDocument(
                userId,
                "memory_item",
                memoryId,
                String.valueOf(candidate.get("content")),
                "用户确认的长期个人记忆",
                now
        );
        audit(userId, "memory.accept", "memory_item", memoryId, Map.of("candidateId", candidateId.toString()));
        return row("select id, memory_type, content, confidence, source, evidence_refs, created_at from memory_item where id = ?", memoryId);
    }

    @Transactional
    public Map<String, Object> rejectMemoryCandidate(UUID candidateId) {
        Instant now = Instant.now();
        jdbc.update("update memory_write_candidate set decision = ?, decided_at = ? where id = ?", "rejected", Timestamp.from(now), candidateId);
        return row("select id, memory_type, content, confidence, decision, decided_at from memory_write_candidate where id = ?", candidateId);
    }

    @Transactional
    public DemoAgentMessageResponse sendAgentMessage(DemoAgentMessageRequest request) {
        Instant now = Instant.now();
        UUID sessionId = getOrCreateAgentSession(request.userId(), request.conversationSessionId(), now);
        UUID userMessageId = UUID.randomUUID();
        jdbc.update("""
                        insert into agent_message (
                            id, conversation_session_id, user_id, role, content_type, content, safety_level, created_at
                        ) values (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                userMessageId, sessionId, request.userId(), "user", "text", request.content(), "normal", Timestamp.from(now));
        jdbc.update("update conversation_session set last_message_at = ? where id = ?", Timestamp.from(now), sessionId);

        UUID runId = UUID.randomUUID();
        jdbc.update("""
                        insert into agent_run (
                            id, conversation_session_id, trigger_message_id, status, context_window_start, context_window_end,
                            safety_result, created_at
                        ) values (?, ?, ?, ?, ?, ?, %s, ?)
                        """.formatted(JSONB),
                runId, sessionId, userMessageId, "running", Timestamp.from(now.minus(7, ChronoUnit.DAYS)), Timestamp.from(now),
                json(Map.of("level", "normal")), Timestamp.from(now));

        List<Map<String, Object>> recalled = recallContext(request.userId(), request.content());
        persistRecallEvents(runId, recalled);
        AgentReplyResponse reply = modelServiceClient.generateReply(new AgentReplyRequest(
                UUID.randomUUID().toString(),
                request.userId(),
                sessionId.toString(),
                userMessageId.toString(),
                request.content(),
                recalled.stream()
                        .map(item -> new AgentReplyRequest.AgentContextItemDto(
                                String.valueOf(item.get("sourceType")),
                                String.valueOf(item.get("sourceId")),
                                String.valueOf(item.get("content")),
                                String.valueOf(item.get("reason")),
                                number(item.get("score"), 0.5)
                        ))
                        .toList()
        ));

        UUID assistantMessageId = UUID.randomUUID();
        String safetyLevel = reply.safety() == null ? "normal" : reply.safety().level();
        jdbc.update("""
                        insert into agent_message (
                            id, conversation_session_id, user_id, role, content_type, content, model_name, safety_level, created_at
                        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                assistantMessageId, sessionId, request.userId(), "assistant", "text", reply.content(), "fake-provider", safetyLevel, Timestamp.from(Instant.now()));
        persistMemoryCandidates(reply.memoryCandidates(), sessionId, null, assistantMessageId, "agent_reply");
        jdbc.update("""
                        update agent_run
                        set status = ?, short_term_memory_ref = ?, retrieved_context_ref = ?, model_response_ref = ?, safety_result = %s, completed_at = ?
                        where id = ?
                        """.formatted(JSONB),
                "completed", "inline://agent-run/" + runId + "/short-term-context", json(recalled),
                "agent-message://" + assistantMessageId, json(reply.safety()), Timestamp.from(Instant.now()), runId);
        jdbc.update("update conversation_session set last_message_at = ? where id = ?", Timestamp.from(Instant.now()), sessionId);
        audit(request.userId(), "agent.reply", "agent_run", runId, Map.of("sessionId", sessionId.toString()));

        return new DemoAgentMessageResponse(
                sessionId.toString(),
                userMessageId.toString(),
                assistantMessageId.toString(),
                runId.toString(),
                reply.content(),
                safetyLevel,
                recalled,
                rows("select id, memory_type, content, confidence, decision, created_at from memory_write_candidate where source_message_id = ? order by created_at desc", assistantMessageId)
        );
    }

    private List<Map<String, Object>> persistSpeakerData(String userId, UUID audioEventId, AnalyzeAudioResponse response, Instant now) {
        List<Map<String, Object>> speakerRefs = new ArrayList<>();
        for (AnalyzeAudioResponse.SpeakerSegmentDto segment : nullSafe(response.segments())) {
            UUID clusterId = getOrCreateSpeakerCluster(userId, segment, now);
            UUID segmentId = UUID.randomUUID();
            jdbc.update("""
                            insert into speaker_segment (
                                id, audio_event_id, speaker_cluster_id, speaker_id, is_user, start_ms, end_ms,
                                transcript, language, confidence, emotion_tags, topic_tags, created_at
                            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, %s, %s, ?)
                            """.formatted(JSONB, JSONB),
                    segmentId, audioEventId, clusterId, segment.speakerId(), true, segment.startMs(), segment.endMs(),
                    segment.transcript(), response.language(), value(segment.confidence(), 0.0),
                    json(nullSafe(segment.emotionTags())), json(nullSafe(segment.topicTags())), Timestamp.from(now));

            speakerRefs.add(linkedMap(
                    "speakerClusterId", clusterId.toString(),
                    "speakerSegmentId", segmentId.toString(),
                    "speakerId", segment.speakerId(),
                    "startMs", segment.startMs(),
                    "endMs", segment.endMs()
            ));
        }
        return speakerRefs;
    }

    private UUID getOrCreateSpeakerCluster(String userId, AnalyzeAudioResponse.SpeakerSegmentDto segment, Instant now) {
        List<UUID> existing = jdbc.query(
                "select id from speaker_cluster where user_id = ? and display_name = ? and deleted_at is null order by last_seen_at desc limit 1",
                (rs, rowNum) -> (UUID) rs.getObject("id"),
                userId, "本人"
        );
        if (!existing.isEmpty()) {
            UUID id = existing.get(0);
            jdbc.update("""
                            update speaker_cluster
                            set last_seen_at = ?, match_confidence_summary = %s, updated_at = ?
                            where id = ?
                            """.formatted(JSONB),
                    Timestamp.from(now), json(Map.of("lastSegmentConfidence", value(segment.confidence(), 0.0), "source", "self_assigned")),
                    Timestamp.from(now), id);
            return id;
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                        insert into speaker_cluster (
                            id, user_id, display_name, status, created_from, first_seen_at, last_seen_at,
                            match_confidence_summary, user_labeled, created_at, updated_at
                        ) values (?, ?, ?, ?, ?, ?, ?, %s, ?, ?, ?)
                        """.formatted(JSONB),
                id, userId, "本人", "known", "self_assigned", Timestamp.from(now), Timestamp.from(now),
                json(Map.of("initialSegmentConfidence", value(segment.confidence(), 0.0), "source", "self_assigned")),
                true, Timestamp.from(now), Timestamp.from(now));
        audit(userId, "speaker_cluster.created", "speaker_cluster", id, Map.of("displayName", "本人"));
        return id;
    }

    private void persistPersonInsights(String userId, List<Map<String, Object>> speakerRefs, Instant now) {
        List<String> clusterIds = speakerRefs.stream()
                .map(ref -> String.valueOf(ref.get("speakerClusterId")))
                .distinct()
                .toList();
        for (String clusterIdText : clusterIds) {
            UUID clusterId = UUID.fromString(clusterIdText);
            Map<String, Object> speaker = row("select display_name from speaker_cluster where id = ?", clusterId);
            String displayName = String.valueOf(speaker.getOrDefault("displayName", "这个人"));
            String summary = personInsightService.summarizeInteraction(displayName, 1);
            UUID insightId = UUID.randomUUID();
            jdbc.update("""
                            insert into person_insight (
                                id, user_id, speaker_cluster_id, insight_type, time_window_start, time_window_end,
                                summary, evidence_refs, confidence, safety_level, created_at
                            ) values (?, ?, ?, ?, ?, ?, ?, %s, ?, ?, ?)
                            """.formatted(JSONB),
                    insightId, userId, clusterId, "interaction_summary", Timestamp.from(now.minus(1, ChronoUnit.HOURS)),
                    Timestamp.from(now), summary, json(speakerRefs), 0.66, "normal", Timestamp.from(now));
            tryIndexVectorDocument(userId, "person_insight", insightId, summary, "周围人物互动洞察", now);
        }
    }

    private void persistMemoryCandidates(
            List<AnalyzeAudioResponse.MemoryCandidateDto> candidates,
            UUID conversationSessionId,
            UUID conversationMemoryId,
            UUID sourceMessageId,
            String sourceType
    ) {
        for (AnalyzeAudioResponse.MemoryCandidateDto candidate : nullSafe(candidates)) {
            String decision = memoryDecisionService.decide(sourceType, blankToDefault(candidate.sensitivity(), "normal"), value(candidate.confidence(), 0.0));
            jdbc.update("""
                            insert into memory_write_candidate (
                                id, conversation_session_id, conversation_memory_id, source_message_id, source_type, memory_type,
                                content, confidence, decision, decision_reason, created_at
                            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    UUID.randomUUID(), conversationSessionId, conversationMemoryId, sourceMessageId, sourceType, candidate.memoryType(),
                    candidate.content(), value(candidate.confidence(), 0.0), decision, "demo_pipeline", Timestamp.from(Instant.now()));
        }
    }

    private List<Map<String, Object>> recallContext(String userId, String query) {
        VectorSearchResponse response = modelServiceClient.searchVectors(new VectorSearchRequest(
                UUID.randomUUID().toString(),
                userId,
                query,
                8
        ));
        return nullSafe(response.items()).stream()
                .map(item -> contextItem(item.sourceType(), item.sourceId(), item.content(), item.reason(), number(item.score(), 0.5)))
                .toList();
    }

    private void persistRecallEvents(UUID runId, List<Map<String, Object>> recalled) {
        int rank = 1;
        for (Map<String, Object> item : recalled) {
            String sourceType = String.valueOf(item.get("sourceType"));
            UUID sourceId = parseUuid(String.valueOf(item.get("sourceId")));
            UUID memoryItemId = "memory_item".equals(sourceType) ? sourceId : null;
            UUID conversationMemoryId = "conversation_memory".equals(sourceType) ? sourceId : null;
            jdbc.update("""
                            insert into memory_recall_event (
                                id, agent_run_id, recall_type, memory_item_id, conversation_memory_id, rank, reason, score, created_at
                            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    UUID.randomUUID(), runId, sourceType, memoryItemId, conversationMemoryId, rank,
                    String.valueOf(item.get("reason")), number(item.get("score"), 0.5), Timestamp.from(Instant.now()));
            rank++;
        }
    }

    private void tryIndexVectorDocument(String userId, String sourceType, UUID sourceId, String content, String reason, Instant createdAt) {
        try {
            modelServiceClient.upsertVectors(new VectorUpsertRequest(
                    UUID.randomUUID().toString(),
                    List.of(new VectorDocumentDto(
                            sourceType + "_" + sourceId,
                            userId,
                            sourceType,
                            sourceId.toString(),
                            content,
                            reason,
                            createdAt.toString()
                    ))
            ));
        } catch (RuntimeException error) {
            audit(userId, "vector.index_failed", sourceType, sourceId, Map.of(
                    "errorType", error.getClass().getSimpleName(),
                    "message", trim(error.getMessage(), 300)
            ));
        }
    }

    private UUID getOrCreateAgentSession(String userId, String requestedSessionId, Instant now) {
        if (requestedSessionId != null && !requestedSessionId.isBlank()) {
            UUID id = UUID.fromString(requestedSessionId);
            Integer count = jdbc.queryForObject("select count(*) from conversation_session where id = ? and user_id = ?", Integer.class, id, userId);
            if (count != null && count > 0) {
                return id;
            }
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                        insert into conversation_session (id, user_id, title, session_type, started_at, last_message_at, status, source)
                        values (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                id, userId, "Demo Agent 会话", "demo_chat", Timestamp.from(now), Timestamp.from(now), "active", "web_demo");
        return id;
    }

    private Map<String, Object> contextItem(String sourceType, Object sourceId, Object content, String reason, double score) {
        return linkedMap(
                "sourceType", sourceType,
                "sourceId", String.valueOf(sourceId),
                "content", String.valueOf(content),
                "reason", reason,
                "score", score
        );
    }

    private String combinedTranscript(List<Map<String, Object>> segments) {
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> segment : segments) {
            String transcript = String.valueOf(segment.getOrDefault("transcript", "")).trim();
            if (transcript.isBlank()) {
                continue;
            }
            String displayName = String.valueOf(segment.getOrDefault("displayName", "Unknown Person")).trim();
            lines.add(displayName + ": " + transcript);
        }
        return String.join("\n", lines);
    }

    private String summarizeTranscript(String transcript) {
        String compact = transcript.replaceAll("\\s+", " ").trim();
        if (compact.length() <= 240) {
            return "本次流式会话主要内容：" + compact;
        }
        return "本次流式会话主要内容：" + compact.substring(0, 240) + "...";
    }

    private UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (RuntimeException error) {
            return null;
        }
    }

    private Map<String, Object> row(String sql, Object... args) {
        List<Map<String, Object>> result = rows(sql, args);
        return result.isEmpty() ? Map.of() : result.get(0);
    }

    private List<Map<String, Object>> rows(String sql, Object... args) {
        return jdbc.query(sql, (rs, rowNum) -> {
            ResultSetMetaData metadata = rs.getMetaData();
            Map<String, Object> row = new LinkedHashMap<>();
            for (int index = 1; index <= metadata.getColumnCount(); index++) {
                String key = toCamelCase(metadata.getColumnLabel(index));
                Object value = rs.getObject(index);
                row.put(key, normalize(value));
            }
            return row;
        }, args);
    }

    private Object normalize(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant().toString();
        }
        if (value instanceof UUID uuid) {
            return uuid.toString();
        }
        if ("org.postgresql.util.PGobject".equals(value.getClass().getName())) {
            return normalizePostgresObject(value);
        }
        return value;
    }

    private Object normalizePostgresObject(Object value) {
        try {
            String jsonValue = String.valueOf(value.getClass().getMethod("getValue").invoke(value));
            return JSON.parse(jsonValue);
        } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException | JSONException error) {
            return String.valueOf(value);
        }
    }

    private void audit(String userId, String action, String targetType, UUID targetId, Map<String, Object> metadata) {
        jdbc.update("""
                        insert into audit_log (id, user_id, actor_type, action, target_type, target_id, metadata, created_at)
                        values (?, ?, ?, ?, ?, ?, %s, ?)
                        """.formatted(JSONB),
                UUID.randomUUID(), userId, "user", action, targetType, targetId, json(metadata), Timestamp.from(Instant.now()));
    }

    private String json(Object value) {
        return toJson(value == null ? Map.of() : value);
    }

    private static <T> List<T> nullSafe(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static String toJson(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String string) {
            return "\"" + escapeJson(string) + "\"";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof UUID || value instanceof Instant) {
            return "\"" + escapeJson(String.valueOf(value)) + "\"";
        }
        if (value instanceof Map<?, ?> map) {
            List<String> parts = new ArrayList<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                parts.add(toJson(String.valueOf(entry.getKey())) + ":" + toJson(entry.getValue()));
            }
            return "{" + String.join(",", parts) + "}";
        }
        if (value instanceof Iterable<?> iterable) {
            List<String> parts = new ArrayList<>();
            for (Object item : iterable) {
                parts.add(toJson(item));
            }
            return "[" + String.join(",", parts) + "]";
        }
        if (value.getClass().isRecord()) {
            Map<String, Object> recordValues = new LinkedHashMap<>();
            for (RecordComponent component : value.getClass().getRecordComponents()) {
                try {
                    recordValues.put(component.getName(), component.getAccessor().invoke(value));
                } catch (ReflectiveOperationException error) {
                    throw new IllegalArgumentException("failed to serialize record component", error);
                }
            }
            return toJson(recordValues);
        }
        return toJson(String.valueOf(value));
    }

    private static String escapeJson(String value) {
        StringBuilder escaped = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private static Timestamp windowTimestamp(Instant value, Instant defaultValue) {
        Instant resolved = value != null ? value : defaultValue;
        return resolved == null ? null : Timestamp.from(resolved);
    }

    private static Instant timestampValue(Object value, Instant defaultValue) {
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        return defaultValue;
    }

    private static String trim(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static double value(Double value, double defaultValue) {
        return value == null ? defaultValue : value;
    }

    private static double number(Object value, double defaultValue) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException error) {
            return defaultValue;
        }
    }

    private static String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static String safeFileName(String fileName) {
        return blankToDefault(fileName, "browser-recording.webm");
    }

    private static String fileNameCodec(String fileName) {
        if (fileName == null) {
            return "webm";
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".wav")) {
            return "wav";
        }
        if (lower.endsWith(".mp3")) {
            return "mp3";
        }
        if (lower.endsWith(".ogg")) {
            return "ogg";
        }
        return "webm";
    }

    private static String toCamelCase(String label) {
        String lower = label.toLowerCase(Locale.ROOT);
        StringBuilder result = new StringBuilder();
        boolean uppercaseNext = false;
        for (char character : lower.toCharArray()) {
            if (character == '_') {
                uppercaseNext = true;
                continue;
            }
            if (uppercaseNext) {
                result.append(Character.toUpperCase(character));
                uppercaseNext = false;
            } else {
                result.append(character);
            }
        }
        return result.toString();
    }

    private static Map<String, Object> linkedMap(Object... keyValues) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < keyValues.length; index += 2) {
            result.put(String.valueOf(keyValues[index]), keyValues[index + 1]);
        }
        return result;
    }
}
