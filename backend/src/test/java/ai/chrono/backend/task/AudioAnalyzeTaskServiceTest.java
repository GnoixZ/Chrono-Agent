package ai.chrono.backend.task;

import ai.chrono.backend.demo.DemoAudioResult;
import ai.chrono.backend.demo.DemoPipelineService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AudioAnalyzeTaskServiceTest {
    @Test
    void submitAudioAnalyzeJobRunsOnBackgroundExecutor() throws Exception {
        DemoPipelineService demoPipelineService = mock(DemoPipelineService.class);
        AudioAnalyzeTaskService service = new AudioAnalyzeTaskService(demoPipelineService);
        UUID audioEventId = UUID.randomUUID();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        DemoAudioResult expected = new DemoAudioResult(
                audioEventId.toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                "completed",
                "title",
                "overview",
                List.of(),
                List.of(),
                List.of()
        );

        when(demoPipelineService.processPendingAudio(audioEventId)).thenAnswer(invocation -> {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            return expected;
        });

        var future = service.submitAudioAnalyzeJob(audioEventId);

        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(future.isDone()).isFalse();

        release.countDown();

        assertThat(future.get(2, TimeUnit.SECONDS)).isEqualTo(expected);
        service.shutdown();
    }
}
