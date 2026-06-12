package ai.chrono.backend.health;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class HealthService {
    private static final String JSONB = "cast(? as jsonb)";
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final JdbcTemplate jdbc;

    public HealthService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public HealthEventResponse create(HealthEventRequest request) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        String source = blankToDefault(request.source(), "manual");
        String valueText = blankToNull(request.valueText());
        String unit = blankToNull(request.unit());

        jdbc.update(
                """
                        insert into health_event (
                            id, user_id, event_type, measured_at, value_numeric, value_text, unit, source, metadata, created_at
                        ) values (?, ?, ?, ?, ?, ?, ?, ?, %s, ?)
                        """.formatted(JSONB),
                id,
                request.userId(),
                request.eventType(),
                Timestamp.from(request.measuredAt()),
                request.valueNumeric(),
                valueText,
                unit,
                source,
                "{}",
                Timestamp.from(now)
        );

        return new HealthEventResponse(
                id.toString(),
                request.userId(),
                request.eventType(),
                request.measuredAt().toString(),
                request.valueNumeric(),
                valueText,
                unit,
                source,
                displayValue(valueText, request.valueNumeric(), unit),
                now.toString()
        );
    }

    @Transactional(readOnly = true)
    public List<HealthEventResponse> list(String userId, Instant start, Instant end, String eventType, Integer limit) {
        StringBuilder sql = new StringBuilder(
                """
                        select id, user_id, event_type, measured_at, value_numeric, value_text, unit, source, created_at
                        from health_event
                        where user_id = ?
                        """
        );
        List<Object> args = new ArrayList<>();
        args.add(userId);

        if (eventType != null && !eventType.isBlank()) {
            sql.append(" and event_type = ?");
            args.add(eventType);
        }
        if (start != null) {
            sql.append(" and measured_at >= ?");
            args.add(Timestamp.from(start));
        }
        if (end != null) {
            sql.append(" and measured_at <= ?");
            args.add(Timestamp.from(end));
        }

        sql.append(" order by measured_at desc limit ?");
        args.add(resolveLimit(limit));

        return jdbc.query(sql.toString(), (resultSet, rowNum) -> map(resultSet), args.toArray());
    }

    private HealthEventResponse map(ResultSet resultSet) throws SQLException {
        Timestamp measuredAt = resultSet.getTimestamp("measured_at");
        Timestamp createdAt = resultSet.getTimestamp("created_at");
        Double valueNumeric = readDouble(resultSet, "value_numeric");
        String valueText = resultSet.getString("value_text");
        String unit = resultSet.getString("unit");

        return new HealthEventResponse(
                resultSet.getObject("id", UUID.class).toString(),
                resultSet.getString("user_id"),
                resultSet.getString("event_type"),
                measuredAt == null ? null : measuredAt.toInstant().toString(),
                valueNumeric,
                valueText,
                unit,
                resultSet.getString("source"),
                displayValue(valueText, valueNumeric, unit),
                createdAt == null ? null : createdAt.toInstant().toString()
        );
    }

    private static Double readDouble(ResultSet resultSet, String column) throws SQLException {
        double value = resultSet.getDouble(column);
        return resultSet.wasNull() ? null : value;
    }

    private static int resolveLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        return Math.min(Math.max(limit, 1), MAX_LIMIT);
    }

    private static String displayValue(String valueText, Double valueNumeric, String unit) {
        Object value = valueText != null && !valueText.isBlank() ? valueText : valueNumeric;
        String suffix = unit == null ? "" : unit;
        return String.valueOf(value == null ? "" : value) + suffix;
    }

    private static String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
