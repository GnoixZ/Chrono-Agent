package ai.chrono.backend.modelclient.dto;

import com.alibaba.fastjson2.annotation.JSONField;
import java.util.List;

public record AgentReplyResponse(
        String content,
        AnalyzeAudioResponse.SafetyResultDto safety,
        @JSONField(name = "memory_candidates") List<AnalyzeAudioResponse.MemoryCandidateDto> memoryCandidates
) {
}
