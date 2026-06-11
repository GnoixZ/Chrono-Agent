package ai.chrono.backend.speaker;

import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class SpeakerLabelSuggestionService {
    private static final Pattern CHINESE_SELF_INTRO =
            Pattern.compile("(我是|我叫|我的名字是)\\s*([\\u4e00-\\u9fa5A-Za-z0-9_]{2,20})");

    public Optional<String> suggestFromTranscript(String transcript) {
        Matcher matcher = CHINESE_SELF_INTRO.matcher(transcript);
        if (!matcher.find()) {
            return Optional.empty();
        }
        return Optional.of(matcher.group(2));
    }
}
