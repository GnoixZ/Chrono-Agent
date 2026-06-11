package ai.chrono.backend.speaker;

import org.springframework.stereotype.Service;

@Service
public class PersonInsightService {
    public String summarizeInteraction(String displayName, int interactionCount) {
        String name = displayName == null || displayName.isBlank() ? "这个人" : displayName;
        return name + "最近出现 " + interactionCount + " 次。这个统计只能作为互动复盘线索，不能代表真实身份或关系判断。";
    }
}
