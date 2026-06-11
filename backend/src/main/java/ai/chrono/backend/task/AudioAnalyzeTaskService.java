package ai.chrono.backend.task;

import ai.chrono.backend.demo.DemoAudioResult;
import ai.chrono.backend.demo.DemoPipelineService;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class AudioAnalyzeTaskService {
    private final DemoPipelineService demoPipelineService;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    public AudioAnalyzeTaskService(DemoPipelineService demoPipelineService) {
        this.demoPipelineService = demoPipelineService;
    }

    public CompletableFuture<DemoAudioResult> submitAudioAnalyzeJob(UUID audioEventId) {
        return CompletableFuture.supplyAsync(() -> demoPipelineService.processPendingAudio(audioEventId), executor);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}
