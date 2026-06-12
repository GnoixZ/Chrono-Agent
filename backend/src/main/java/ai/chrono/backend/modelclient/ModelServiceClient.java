package ai.chrono.backend.modelclient;

import ai.chrono.backend.modelclient.dto.AgentReplyRequest;
import ai.chrono.backend.modelclient.dto.AgentReplyResponse;
import ai.chrono.backend.modelclient.dto.AnalyzeAudioRequest;
import ai.chrono.backend.modelclient.dto.AnalyzeAudioResponse;
import ai.chrono.backend.modelclient.dto.IncrementalTranscriptRequest;
import ai.chrono.backend.modelclient.dto.IncrementalTranscriptResponse;
import ai.chrono.backend.modelclient.dto.VectorSearchRequest;
import ai.chrono.backend.modelclient.dto.VectorSearchResponse;
import ai.chrono.backend.modelclient.dto.VectorUpsertRequest;
import ai.chrono.backend.modelclient.dto.VectorUpsertResponse;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

@Component
public class ModelServiceClient {
    private final RestTemplate restTemplate;
    private final String baseUrl;

    public ModelServiceClient(@Value("${chrono.model-service.base-url:http://localhost:8000}") String baseUrl) {
        this.restTemplate = new RestTemplate();
        this.baseUrl = trimTrailingSlash(baseUrl);
    }

    public AnalyzeAudioResponse analyzeAudio(AnalyzeAudioRequest request) {
        String response = postJson("/v1/audio/analyze", request);
        return readJson(response, AnalyzeAudioResponse.class);
    }

    public IncrementalTranscriptResponse incrementalTranscript(IncrementalTranscriptRequest request) {
        String response = postJson("/v1/audio/transcript", request);
        return readJson(response, IncrementalTranscriptResponse.class);
    }

    public AgentReplyResponse generateReply(AgentReplyRequest request) {
        String response = postJson("/v1/agent/reply", request);
        return readJson(response, AgentReplyResponse.class);
    }

    public VectorUpsertResponse upsertVectors(VectorUpsertRequest request) {
        String response = postJson("/v1/vector/upsert", request);
        return readJson(response, VectorUpsertResponse.class);
    }

    public VectorSearchResponse searchVectors(VectorSearchRequest request) {
        String response = postJson("/v1/vector/search", request);
        return readJson(response, VectorSearchResponse.class);
    }

    private String postJson(String path, Object request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(JSON.toJSONString(request), headers);
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    baseUrl + path,
                    HttpMethod.POST,
                    entity,
                    String.class
            );
            return response.getBody();
        } catch (RestClientResponseException error) {
            throw new IllegalStateException("model service returned "
                    + error.getStatusCode().value() + ": " + error.getResponseBodyAsString(), error);
        } catch (RestClientException error) {
            throw new IllegalStateException("failed to call model service", error);
        }
    }

    private <T> T readJson(String value, Class<T> targetType) {
        try {
            return JSON.parseObject(value, targetType);
        } catch (JSONException error) {
            throw new IllegalArgumentException("failed to parse model response", error);
        }
    }

    private static String trimTrailingSlash(String value) {
        if (value.endsWith("/")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }
}
