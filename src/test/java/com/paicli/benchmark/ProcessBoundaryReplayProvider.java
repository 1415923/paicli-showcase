package com.paicli.benchmark;

import com.paicli.model.AgentMessage;
import com.paicli.model.ModelResponse;
import com.paicli.model.ToolDefinition;
import com.paicli.provider.PreparedProviderTurn;
import com.paicli.provider.PreparedTurnProvider;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/** Network-free Provider used only to prove the formal process boundary. */
final class ProcessBoundaryReplayProvider implements PreparedTurnProvider {
    private final String taskId;

    ProcessBoundaryReplayProvider(String taskId) {
        this.taskId = taskId;
    }

    @Override
    public PreparedProviderTurn prepareTurn(String operation, List<AgentMessage> messages,
            List<ToolDefinition> tools) {
        String digest = sha256(taskId + "\n" + operation + "\n"
                + messages.size() + "\n" + tools.size());
        return new PreparedProviderTurn(digest, () -> "PLAN".equals(operation)
                ? ModelResponse.finalAnswer("""
                    {"acceptanceCriteria":["offline process-boundary admission"],
                     "assumptions":[],"risks":[],
                     "verificationStrategy":"provider-disabled process-boundary proof",
                     "steps":[]}
                    """)
                : ModelResponse.finalAnswer("offline process-boundary proof"));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }
}
