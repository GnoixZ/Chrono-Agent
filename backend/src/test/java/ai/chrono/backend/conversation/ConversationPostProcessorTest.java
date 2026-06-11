package ai.chrono.backend.conversation;

import ai.chrono.backend.modelclient.dto.AnalyzeAudioResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationPostProcessorTest {
    @Test
    void discardsLowValueConversation() {
        AnalyzeAudioResponse response = new AnalyzeAudioResponse(
                "zh",
                List.of(),
                List.of(),
                new AnalyzeAudioResponse.ConversationSummaryDto(
                        "",
                        "",
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        true,
                        "blank_or_low_value_audio"
                ),
                List.of(),
                new AnalyzeAudioResponse.SafetyResultDto("normal", false, null)
        );

        ConversationPostProcessor processor = new ConversationPostProcessor();
        ConversationPostProcessor.PostProcessingDecision decision = processor.decide(response);

        assertThat(decision.status()).isEqualTo("discarded");
        assertThat(decision.reason()).isEqualTo("blank_or_low_value_audio");
    }
}
