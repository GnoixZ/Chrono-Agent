package ai.chrono.backend.common;

public final class LogSanitizer {
    private LogSanitizer() {
    }

    public static String summarize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 24) {
            return normalized;
        }
        return normalized.substring(0, 24) + "...";
    }
}
