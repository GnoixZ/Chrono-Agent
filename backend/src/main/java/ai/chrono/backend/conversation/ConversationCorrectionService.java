package ai.chrono.backend.conversation;

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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ConversationCorrectionService {
    private static final String JSONB = "cast(? as jsonb)";

    private final JdbcTemplate jdbc;

    public ConversationCorrectionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Map<String, Object> merge(ConversationMergeRequest request) {
        List<UUID> ids = request.conversationMemoryIds().stream().map(UUID::fromString).toList();
        if (ids.size() < 2) {
            throw new IllegalArgumentException("at least two conversations are required");
        }
        List<Map<String, Object>> sources = loadConversationMemories(request.userId(), ids);
        if (sources.size() != ids.size()) {
            throw new IllegalArgumentException("conversation memory not found for user");
        }
        Instant now = Instant.now();
        UUID mergedId = UUID.randomUUID();
        Map<String, Object> first = sources.get(0);
        String title = "合并会话：" + stringValue(first.get("title"), "未命名会话");
        String overview = mergeOverview(sources);
        List<Map<String, Object>> evidenceRefs = sources.stream()
                .map(source -> linkedMap("type", "conversation_memory", "id", source.get("id")))
                .toList();

        insertDerivedConversation(
                mergedId,
                request.userId(),
                "conversation_merge",
                parseUuid(first.get("sourceAudioEventId")),
                parseUuid(first.get("sourceStreamSessionId")),
                timestampValue(first.get("startedAt"), now),
                timestampValue(first.get("endedAt"), now),
                title,
                overview,
                "completed",
                false,
                null,
                null,
                json(evidenceRefs),
                now
        );
        for (UUID id : ids) {
            jdbc.update("update conversation_memory set discarded = ?, discard_reason = ?, updated_at = ? where id = ? and user_id = ?",
                    true, "merged_into:" + mergedId, Timestamp.from(now), id, request.userId());
        }
        audit(request.userId(), "conversation.merge", "conversation_memory", mergedId, Map.of(
                "sourceConversationMemoryIds", ids.stream().map(UUID::toString).toList(),
                "reason", blankToDefault(request.reason(), "manual_merge")
        ));
        return row("select id, source_type, title, overview, evidence_refs, created_at from conversation_memory where id = ?", mergedId);
    }

    @Transactional
    public Map<String, Object> split(UUID conversationMemoryId, ConversationSplitRequest request) {
        Map<String, Object> source = loadConversationMemory(request.userId(), conversationMemoryId);
        List<ConversationSplitRequest.Part> parts = normalizedParts(source, request.parts());
        Instant now = Instant.now();
        List<String> newIds = new ArrayList<>();
        int index = 1;
        for (ConversationSplitRequest.Part part : parts) {
            UUID splitId = UUID.randomUUID();
            insertDerivedConversation(
                    splitId,
                    request.userId(),
                    "conversation_split",
                    parseUuid(source.get("sourceAudioEventId")),
                    parseUuid(source.get("sourceStreamSessionId")),
                    timestampValue(source.get("startedAt"), now),
                    timestampValue(source.get("endedAt"), now),
                    blankToDefault(part.title(), "拆分会话 " + index),
                    blankToDefault(part.overview(), "由原会话拆分出的第 " + index + " 段。"),
                    "completed",
                    false,
                    null,
                    conversationMemoryId,
                    json(List.of(linkedMap("type", "conversation_memory", "id", conversationMemoryId.toString(), "splitPart", index))),
                    now
            );
            newIds.add(splitId.toString());
            index++;
        }
        jdbc.update("update conversation_memory set discarded = ?, discard_reason = ?, updated_at = ? where id = ? and user_id = ?",
                true, "split_into:" + String.join(",", newIds), Timestamp.from(now), conversationMemoryId, request.userId());
        audit(request.userId(), "conversation.split", "conversation_memory", conversationMemoryId, Map.of(
                "createdConversationMemoryIds", newIds,
                "reason", blankToDefault(request.reason(), "manual_split")
        ));
        return linkedMap("sourceConversationMemoryId", conversationMemoryId.toString(), "createdConversationMemoryIds", newIds);
    }

    @Transactional
    public Map<String, Object> reprocess(UUID conversationMemoryId, ConversationReprocessRequest request) {
        Map<String, Object> source = loadConversationMemory(request.userId(), conversationMemoryId);
        UUID streamSessionId = parseUuid(source.get("sourceStreamSessionId"));
        String overview = streamSessionId == null
                ? stringValue(source.get("overview"), "")
                : summarizeStreamSession(streamSessionId, stringValue(source.get("overview"), ""));
        Instant now = Instant.now();
        jdbc.update("""
                        update conversation_memory
                        set overview = ?, post_processing_status = ?, processing_attempts = processing_attempts + 1, updated_at = ?
                        where id = ? and user_id = ?
                        """,
                overview, "completed", Timestamp.from(now), conversationMemoryId, request.userId());
        audit(request.userId(), "conversation.reprocess", "conversation_memory", conversationMemoryId, Map.of(
                "reason", blankToDefault(request.reason(), "manual_reprocess")
        ));
        return row("select id, title, overview, processing_attempts, post_processing_status, updated_at from conversation_memory where id = ?", conversationMemoryId);
    }

    private List<Map<String, Object>> loadConversationMemories(String userId, List<UUID> ids) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (UUID id : ids) {
            result.add(loadConversationMemory(userId, id));
        }
        return result;
    }

    private Map<String, Object> loadConversationMemory(String userId, UUID id) {
        List<Map<String, Object>> rows = rows("""
                select id, source_type, source_audio_event_id, source_stream_session_id, started_at, ended_at,
                       title, overview, speaker_refs, health_refs, topic_tags, emotion_tags, suggested_actions,
                       suggested_events, evidence_refs
                from conversation_memory
                where id = ? and user_id = ? and deleted_at is null
                """, id, userId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("conversation memory not found for user");
        }
        return rows.get(0);
    }

    private void insertDerivedConversation(
            UUID id,
            String userId,
            String sourceType,
            UUID sourceAudioEventId,
            UUID sourceStreamSessionId,
            Instant startedAt,
            Instant endedAt,
            String title,
            String overview,
            String status,
            boolean discarded,
            String discardReason,
            UUID correctionOfId,
            String evidenceRefs,
            Instant now
    ) {
        jdbc.update("""
                        insert into conversation_memory (
                            id, user_id, source_type, source_audio_event_id, source_stream_session_id, correction_of_id,
                            started_at, ended_at, title, overview, language, category, status, post_processing_status,
                            processing_attempts, discarded, discard_reason, visibility, transcript_ref, speaker_refs,
                            health_refs, topic_tags, emotion_tags, suggested_actions, suggested_events, evidence_refs,
                            created_at, updated_at
                        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, %s, %s, %s, %s, %s, %s, %s, ?, ?)
                        """.formatted(JSONB, JSONB, JSONB, JSONB, JSONB, JSONB, JSONB),
                id, userId, sourceType, sourceAudioEventId, sourceStreamSessionId, correctionOfId,
                Timestamp.from(startedAt), Timestamp.from(endedAt), title, overview, "zh", "life_log", status,
                "completed", 1, discarded, discardReason, "private", "inline://conversation-correction/" + id,
                json(List.of()), json(List.of()), json(List.of("manual_correction")), json(List.of()),
                json(List.of()), json(List.of()), evidenceRefs, Timestamp.from(now), Timestamp.from(now));
    }

    private String mergeOverview(List<Map<String, Object>> sources) {
        List<String> parts = new ArrayList<>();
        for (Map<String, Object> source : sources) {
            parts.add(stringValue(source.get("title"), "未命名会话") + "：" + stringValue(source.get("overview"), ""));
        }
        return String.join("\n", parts);
    }

    private List<ConversationSplitRequest.Part> normalizedParts(Map<String, Object> source, List<ConversationSplitRequest.Part> requestedParts) {
        if (requestedParts != null && requestedParts.size() >= 2) {
            return requestedParts;
        }
        String overview = stringValue(source.get("overview"), "");
        int midpoint = Math.max(1, overview.length() / 2);
        return List.of(
                new ConversationSplitRequest.Part(stringValue(source.get("title"), "会话") + " A", overview.substring(0, midpoint).trim()),
                new ConversationSplitRequest.Part(stringValue(source.get("title"), "会话") + " B", overview.substring(midpoint).trim())
        );
    }

    private String summarizeStreamSession(UUID streamSessionId, String fallback) {
        List<Map<String, Object>> rows = rows("""
                select ss.transcript, sc.display_name
                from speaker_segment ss
                join audio_event ae on ae.id = ss.audio_event_id
                left join speaker_cluster sc on sc.id = ss.speaker_cluster_id
                where ae.stream_session_id = ?
                order by ae.window_index nulls last, ss.start_ms
                """, streamSessionId);
        if (rows.isEmpty()) {
            return fallback;
        }
        StringBuilder builder = new StringBuilder("重算后的流式会话主要内容：");
        for (Map<String, Object> row : rows) {
            builder.append(stringValue(row.get("displayName"), "Unknown Person"))
                    .append("：")
                    .append(stringValue(row.get("transcript"), ""))
                    .append(" ");
        }
        String value = builder.toString().trim();
        return value.length() <= 500 ? value : value.substring(0, 500) + "...";
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
                row.put(toCamelCase(metadata.getColumnLabel(index)), normalize(rs.getObject(index)));
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

    private static UUID parseUuid(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(String.valueOf(value));
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static Instant timestampValue(Object value, Instant defaultValue) {
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof String string && !string.isBlank()) {
            try {
                return Instant.parse(string);
            } catch (RuntimeException ignored) {
            }
        }
        return defaultValue;
    }

    private static String stringValue(Object value, String defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? defaultValue : text;
    }

    private static String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static String toCamelCase(String label) {
        String lower = label.toLowerCase();
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
