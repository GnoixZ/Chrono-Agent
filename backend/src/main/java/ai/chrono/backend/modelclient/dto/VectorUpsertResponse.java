package ai.chrono.backend.modelclient.dto;

import com.alibaba.fastjson2.annotation.JSONField;

public record VectorUpsertResponse(
        @JSONField(name = "indexed_count") Integer indexedCount
) {
}
