package ai.chrono.backend.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LogSanitizerTest {
    @Test
    void truncatesLongSensitiveText() {
        String result = LogSanitizer.summarize("这是一段很长的用户心理状态描述，不应该完整进入日志。");

        assertThat(result).endsWith("...");
        assertThat(result.length()).isLessThan(30);
    }
}
