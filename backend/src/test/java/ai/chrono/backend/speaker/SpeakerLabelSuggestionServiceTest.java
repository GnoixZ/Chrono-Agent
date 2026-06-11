package ai.chrono.backend.speaker;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SpeakerLabelSuggestionServiceTest {
    @Test
    void suggestsLabelFromChineseSelfIntroduction() {
        SpeakerLabelSuggestionService service = new SpeakerLabelSuggestionService();

        assertThat(service.suggestFromTranscript("你好，我叫张三，今天第一次见。"))
                .contains("张三");
    }

    @Test
    void doesNotSuggestWhenNoSelfIntroductionExists() {
        SpeakerLabelSuggestionService service = new SpeakerLabelSuggestionService();

        assertThat(service.suggestFromTranscript("今天会议有点紧张。"))
                .isEmpty();
    }
}
