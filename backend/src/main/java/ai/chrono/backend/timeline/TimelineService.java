package ai.chrono.backend.timeline;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class TimelineService {
    public List<TimelineItem> summarizeWindow(String userId, Instant start, Instant end) {
        return List.of(new TimelineItem(userId, start, end, "当前时间窗口可用于汇总音频、健康和 Agent 事件。"));
    }

    public record TimelineItem(String userId, Instant start, Instant end, String summary) {
    }
}
