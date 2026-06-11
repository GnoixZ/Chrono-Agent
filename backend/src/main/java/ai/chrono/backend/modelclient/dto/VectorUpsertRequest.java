package ai.chrono.backend.modelclient.dto;

import com.alibaba.fastjson2.annotation.JSONField;
import java.util.List;

public record VectorUpsertRequest(
        @JSONField(name = "request_id") String requestId,
        List<VectorDocumentDto> documents
) {
}
