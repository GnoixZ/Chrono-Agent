package ai.chrono.backend.safety;

public class AgentSafetyGuard {
    private final int maxRecallItems;
    private final int maxToolCalls;

    public AgentSafetyGuard(int maxRecallItems, int maxToolCalls) {
        this.maxRecallItems = maxRecallItems;
        this.maxToolCalls = maxToolCalls;
    }

    public void validate(int recallItems, int toolCalls) {
        if (recallItems > maxRecallItems) {
            throw new IllegalArgumentException("recall item limit exceeded");
        }
        if (toolCalls > maxToolCalls) {
            throw new IllegalArgumentException("tool call limit exceeded");
        }
    }
}
