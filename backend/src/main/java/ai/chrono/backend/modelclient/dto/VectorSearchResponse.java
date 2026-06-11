package ai.chrono.backend.modelclient.dto;

import com.alibaba.fastjson2.annotation.JSONField;
import java.util.List;

public record VectorSearchResponse(
        List<VectorSearchResultDto> items
) {
    public record VectorSearchResultDto(
            @JSONField(name = "document_id") String documentId,
            @JSONField(name = "source_type") String sourceType,
            @JSONField(name = "source_id") String sourceId,
            String content,
            String reason,
            Double score
    ) {
    }
}
