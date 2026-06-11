package ai.chrono.backend.demo;

import ai.chrono.backend.audio.AudioStorage;
import ai.chrono.backend.health.HealthEventTypes;
import ai.chrono.backend.memory.MemoryCandidateDecisionService;
import ai.chrono.backend.modelclient.ModelServiceClient;
import ai.chrono.backend.modelclient.dto.AgentReplyRequest;
import ai.chrono.backend.modelclient.dto.AgentReplyResponse;
import ai.chrono.backend.modelclient.dto.AnalyzeAudioRequest;
import ai.chrono.backend.modelclient.dto.AnalyzeAudioResponse;
import ai.chrono.backend.speaker.PersonInsightService;
import ai.chrono.backend.speaker.SpeakerLabelSuggestionService;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class DemoPipelineService {
    private static final String JSONB = "cast(? as jsonb)";

    private final JdbcTemplate jdbc;
    private final AudioStorage audioStorage;
    private final ModelServiceClient modelServiceClient;
    private final MemoryCandidateDecisionService memoryDecisionService;
    private final SpeakerLabelSuggestionService labelSuggestionService;
    private final PersonInsightService personInsightService;

    public DemoPipelineService(
            JdbcTemplate jdbc,
            AudioStorage audioStorage,
            ModelServiceClient modelServiceClient,
            MemoryCandidateDecisionService memoryDecisionService,
            SpeakerLabelSuggestionService labelSuggestionService,
            PersonInsightService personInsightService
    ) {
        this.jdbc = jdbc;
        this.audioStorage = audioStorage;
        this.modelServiceClient = modelServiceClient;
        this.memoryDecisionService = memoryDecisionService;
        this.labelSuggestionService = labelSuggestionService;
        this.personInsightService = personInsightService;
    }

    @Transactional
    public DemoAudioResult processAudio(String userId, String fileName, byte[] bytes, String sourceType, UUID streamSessionId) {
        Instant now = Instant.now();
        AudioStorage.StoredAudio storedAudio = audioStorage.save(new AudioStorage.AudioInput(userId, safeFileName(fileName), bytes));
        UUID audioEventId = UUID.randomUUID();
        UUID modelJobId = UUID.randomUUID();
        String idempotencyKey = "audio_analyze:" + audioEventId;

        jdbc.update("""
                        insert into audio_event (
                            id, user_id, source_type, started_at, ended_at, audio_uri, processing_status,
                            stream_session_id, sample_rate, codec, duration_ms, retention_expires_at, created_at, updated_at
                        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                audioEventId, userId, sourceType, Timestamp.from(now), Timestamp.from(now), storedAudio.audioUri(), "processing",
                streamSessionId, 16000, fileNameCodec(fileName), null, Timestamp.from(now.plus(30, ChronoUnit.DAYS)),
                Timestamp.from(now), Timestamp.from(now));

        jdbc.update("""
                        insert into model_job (
                            id, user_id, job_type, source_ref_type, source_ref_id, status, attempts, next_run_at,
                            request_ref, response_ref, idempotency_key, created_at, updated_at
                        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                modelJobId, userId, "audio_analyze", "audio_event", audioEventId, "running", 1, Timestamp.from(now),
                "audio://" + audioEventId, null, idempotencyKey, Timestamp.from(now), Timestamp.from(now));

        AnalyzeAudioResponse response;
        try {
            response = modelServiceClient.analyzeAudio(new AnalyzeAudioRequest(
                    UUID.randomUUID().toString(),
                    userId,
                    audioEventId.toString(),
                    storedAudio.audioUri(),
                    now.toString(),
                    now.toString(),
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
        UUID conversationMemoryId = UUID.randomUUID();
        List<Map<String, Object>> speakerRefs = persistSpeakerData(userId, audioEventId, response, now);

        jdbc.update("""
                        insert into conversation_memory (
                            id, user_id, source_type, source_audio_event_id, started_at, ended_at, title, overview,
                            language, category, status, post_processing_status, processing_attempts, discarded, discard_reason,
                            visibility, transcript_ref, speaker_refs, health_refs, topic_tags, emotion_tags,
                            suggested_actions, suggested_events, created_at, updated_at
                        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, %s, %s, %s, %s, %s, %s, ?, ?)
                        """.formatted(JSONB, JSONB, JSONB, JSONB, JSONB, JSONB),
                conversationMemoryId, userId, "audio", audioEventId, Timestamp.from(now), Timestamp.from(now),
                response.summary().title(), response.summary().overview(), response.language(), "life_log",
                processingStatus, "completed", 1, discarded, response.summary().discardReason(), "private",
                "inline://speaker-segments/" + audioEventId,
                json(speakerRefs), json(List.of()), json(nullSafe(response.summary().topicTags())), json(nullSafe(response.summary().emotionTags())),
                json(nullSafe(response.summary().suggestedActions())), json(nullSafe(response.summary().suggestedEvents())),
                Timestamp.from(now), Timestamp.from(now));

        persistMemoryCandidates(response.memoryCandidates(), null, conversationMemoryId, null, "model_suggested");
        persistPersonInsights(userId, speakerRefs, now);

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

    @Transactional(readOnly = true)
    public DemoStateResponse getState(String userId) {
        return new DemoStateResponse(
                userId,
                rows("select id, source_type, started_at, ended_at, audio_uri, processing_status, stream_session_id, created_at from audio_event where user_id = ? order by created_at desc limit 20", userId),
                rows("select id, event_type, measured_at, value_numeric, value_text, unit, source, created_at from health_event where user_id = ? order by measured_at desc limit 30", userId),
                rows("select id, source_type, source_audio_event_id, started_at, title, overview, status, discarded, discard_reason, topic_tags, emotion_tags, created_at from conversation_memory where user_id = ? and deleted_at is null order by created_at desc limit 20", userId),
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
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                        insert into health_event (
                            id, user_id, event_type, measured_at, value_numeric, value_text, unit, source, metadata, created_at
                        ) values (?, ?, ?, ?, ?, ?, ?, ?, %s, ?)
                        """.formatted(JSONB),
                id, request.userId(), request.eventType(), Timestamp.from(request.measuredAt()), request.valueNumeric(),
                request.valueText(), request.unit(), blankToDefault(request.source(), "manual"), json(Map.of("demo", true)), Timestamp.from(now));
        audit(request.userId(), "health_event.created", "health_event", id, Map.of("eventType", request.eventType()));
        return row("select id, event_type, measured_at, value_numeric, value_text, unit, source, created_at from health_event where id = ?", id);
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

        List<Map<String, Object>> recalled = recallContext(request.userId());
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
                    segmentId, audioEventId, clusterId, segment.speakerId(), false, segment.startMs(), segment.endMs(),
                    segment.transcript(), response.language(), value(segment.confidence(), 0.0),
                    json(nullSafe(segment.emotionTags())), json(nullSafe(segment.topicTags())), Timestamp.from(now));

            matchingEmbedding(response.speakerEmbeddings(), segment.speakerId()).ifPresent(embedding -> {
                jdbc.update("""
                                insert into speaker_embedding (
                                    id, speaker_cluster_id, audio_event_id, embedding_ref, model_name, quality_score, created_at, expires_at
                                ) values (?, ?, ?, ?, ?, ?, ?, ?)
                                """,
                        UUID.randomUUID(), clusterId, audioEventId, embedding.embeddingRef(), "fake-voiceprint",
                        value(embedding.qualityScore(), 0.0), Timestamp.from(now), Timestamp.from(now.plus(90, ChronoUnit.DAYS)));
            });

            labelSuggestionService.suggestFromTranscript(segment.transcript()).ifPresent(label -> {
                jdbc.update("""
                                update speaker_cluster
                                set label_suggestion = ?, label_suggestion_source = ?, label_suggestion_confidence = ?, updated_at = ?
                                where id = ? and user_labeled = false
                                """,
                        label, "self_introduction_text", 0.72, Timestamp.from(now), clusterId);
                jdbc.update("""
                                insert into speaker_label_suggestion (
                                    id, speaker_cluster_id, suggested_label, source_type, evidence_ref, confidence, status, created_at
                                ) values (?, ?, ?, ?, ?, ?, ?, ?)
                                """,
                        UUID.randomUUID(), clusterId, label, "self_introduction_text", "speaker_segment://" + segmentId, 0.72,
                        "pending", Timestamp.from(now));
            });

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
                "select id from speaker_cluster where user_id = ? and deleted_at is null order by last_seen_at desc limit 1",
                (rs, rowNum) -> (UUID) rs.getObject("id"),
                userId
        );
        if (!existing.isEmpty()) {
            UUID id = existing.get(0);
            jdbc.update("""
                            update speaker_cluster
                            set last_seen_at = ?, match_confidence_summary = %s, updated_at = ?
                            where id = ?
                            """.formatted(JSONB),
                    Timestamp.from(now), json(Map.of("lastSegmentConfidence", value(segment.confidence(), 0.0), "source", "fake_voiceprint")),
                    Timestamp.from(now), id);
            return id;
        }
        int nextIndex = jdbc.queryForObject("select count(*) from speaker_cluster where user_id = ?", Integer.class, userId) + 1;
        UUID id = UUID.randomUUID();
        jdbc.update("""
                        insert into speaker_cluster (
                            id, user_id, display_name, status, created_from, first_seen_at, last_seen_at,
                            match_confidence_summary, user_labeled, created_at, updated_at
                        ) values (?, ?, ?, ?, ?, ?, ?, %s, ?, ?, ?)
                        """.formatted(JSONB),
                id, userId, "Unknown Person " + nextIndex, "unknown", "voice_embedding", Timestamp.from(now), Timestamp.from(now),
                json(Map.of("initialSegmentConfidence", value(segment.confidence(), 0.0), "source", "fake_voiceprint")),
                false, Timestamp.from(now), Timestamp.from(now));
        audit(userId, "speaker_cluster.created", "speaker_cluster", id, Map.of("displayName", "Unknown Person " + nextIndex));
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
            jdbc.update("""
                            insert into person_insight (
                                id, user_id, speaker_cluster_id, insight_type, time_window_start, time_window_end,
                                summary, evidence_refs, confidence, safety_level, created_at
                            ) values (?, ?, ?, ?, ?, ?, ?, %s, ?, ?, ?)
                            """.formatted(JSONB),
                    UUID.randomUUID(), userId, clusterId, "interaction_summary", Timestamp.from(now.minus(1, ChronoUnit.HOURS)),
                    Timestamp.from(now), summary, json(speakerRefs), 0.66, "normal", Timestamp.from(now));
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

    private List<Map<String, Object>> recallContext(String userId) {
        List<Map<String, Object>> recalled = new ArrayList<>();
        rows("select id, title, overview, created_at from conversation_memory where user_id = ? and discarded = false and deleted_at is null order by created_at desc limit 3", userId)
                .forEach(row -> recalled.add(contextItem("conversation_memory", row.get("id"), row.get("overview"), "最近会话记录", 0.92)));
        rows("select id, content, memory_type, created_at from memory_item where user_id = ? and invalid_at is null and deleted_at is null order by created_at desc limit 3", userId)
                .forEach(row -> recalled.add(contextItem("memory_item", row.get("id"), row.get("content"), "长期个人记忆", 0.88)));
        rows("select id, event_type, value_numeric, value_text, unit, measured_at from health_event where user_id = ? order by measured_at desc limit 3", userId)
                .forEach(row -> recalled.add(contextItem("health_event", row.get("id"), healthText(row), "最近健康事件", 0.72)));
        rows("select id, summary, speaker_cluster_id, created_at from person_insight where user_id = ? order by created_at desc limit 3", userId)
                .forEach(row -> recalled.add(contextItem("person_insight", row.get("id"), row.get("summary"), "周围人物洞察", 0.7)));
        return recalled;
    }

    private void persistRecallEvents(UUID runId, List<Map<String, Object>> recalled) {
        int rank = 1;
        for (Map<String, Object> item : recalled) {
            String sourceType = String.valueOf(item.get("sourceType"));
            UUID sourceId = UUID.fromString(String.valueOf(item.get("sourceId")));
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

    private Optional<AnalyzeAudioResponse.SpeakerEmbeddingDto> matchingEmbedding(List<AnalyzeAudioResponse.SpeakerEmbeddingDto> embeddings, Integer speakerId) {
        return nullSafe(embeddings).stream()
                .filter(embedding -> speakerId != null && speakerId.equals(embedding.speakerId()))
                .findFirst();
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

    private String healthText(Map<String, Object> row) {
        Object value = row.get("valueText") != null ? row.get("valueText") : row.get("valueNumeric");
        Object unit = row.get("unit") == null ? "" : row.get("unit");
        return row.get("eventType") + ": " + value + unit;
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
