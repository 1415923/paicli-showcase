package com.paicli.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.paicli.agent.SharedToolBatchPolicy;
import com.paicli.agent.ToolFactProjection;
import com.paicli.context.ContextModels.CapabilityProfile;
import com.paicli.context.ContextModels.ContextInvocation;
import com.paicli.context.ContextModels.Identity;
import com.paicli.context.ContextModels.LimitProvenance;
import com.paicli.context.ContextModels.OperationKind;
import com.paicli.context.DeterministicContextCompiler;
import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.interactive.session.SavedSessionService;
import com.paicli.model.AgentMessage;
import com.paicli.model.ToolCall;
import com.paicli.support.R3ContextTestFixtures;
import com.paicli.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Source-level control proving stale execution results cannot authorize a newer rejected batch. */
class C3ContinuationAuthoritySourceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path workspace;

    @Test
    void staleHistoricalToolResultCannotSubstituteForLatestBatchTerminalResults() throws Exception {
        try (StateStore state = new StateStore(workspace)) {
            var admitted = R3ContextTestFixtures.admit(state, workspace);
            var root = admitted.root();
            List<AgentMessage> messages = new ArrayList<>();
            AgentMessage user = AgentMessage.userGoal("synthetic source authority goal");
            messages.add(user);
            admitted.guard().appendEvent(0, user);

            String oldId = "historical-result";
            AgentMessage oldAction = AgentMessage.actionWithToolCall("old read", oldId,
                    singleCallJson(oldId, "read_file"));
            ToolResult oldResult = ToolFactProjection.bind(
                    ToolResult.success("synthetic historical result"), oldId, "read_file");
            String oldObservationText = ToolFactProjection.appendCanonicalFacts(
                    oldResult.output(), oldResult);
            AgentMessage oldObservation = AgentMessage.observationForToolCall(
                    oldObservationText, oldId);
            messages.add(oldAction);
            messages.add(oldObservation);
            admitted.guard().appendEvent(1, oldAction);
            CanonicalId occurrence = admitted.guard().admitOccurrence(1, oldId,
                    new ToolCall("read_file", Map.of("path", "synthetic"), oldId));
            admitted.guard().recordToolCall(1, oldId, "read_file", "{}",
                    ToolResult.success(oldObservationText));
            admitted.guard().completeOccurrence(occurrence, oldResult);
            admitted.guard().appendEvent(1, oldObservation);

            List<String> latestIds = List.of("latest-a", "latest-b");
            AgentMessage batchAction = AgentMessage.actionWithToolCall("rejected batch",
                    latestIds.getFirst(), batchJson(latestIds));
            AgentMessage first = AgentMessage.observationForToolCall(
                    SharedToolBatchPolicy.notExecutedResult(1, 2), latestIds.get(0));
            AgentMessage second = AgentMessage.observationForToolCall(
                    SharedToolBatchPolicy.notExecutedResult(2, 2), latestIds.get(1));
            messages.add(batchAction);
            messages.add(first);
            messages.add(second);
            admitted.guard().appendEvent(2, batchAction);
            admitted.guard().appendEvent(2, first);
            admitted.guard().appendEvent(2, second);

            Identity identity = new Identity(SavedSessionService.workspaceIdentity(workspace),
                    root.threadId().value(), root.taskId().value(), root.initialRun().id().value(),
                    root.generation().id().value(), CanonicalId.newId(EntityFamily.CONTEXT).value(),
                    root.instructions().manifest().canonicalHash(), null, null);
            ContextInvocation invocation = new ContextInvocation(identity,
                    OperationKind.TOOL_CONTINUATION, "2", null, List.of(), null, null, null,
                    "mock", "model", new CapabilityProfile("mock", 131_072,
                    LimitProvenance.PROVIDER_REPORTED_LIMIT, 128, 32), false,
                    DeterministicContextCompiler.COMPILER_VERSION,
                    DeterministicContextCompiler.POLICY_VERSION);
            var assembled = state.readOnlySnapshot(() -> state.contextSources.assemble(
                    invocation, "synthetic", messages, List.of(), admitted.guard().current()));

            assertEquals(2, assembled.invocation().toolResultSourceIds().size());
            var bound = assembled.sources().sources().stream()
                    .filter(source -> assembled.invocation().toolResultSourceIds()
                            .contains(source.stableSourceId()))
                    .toList();
            assertEquals(latestIds, bound.stream().map(source -> source.toolCallId()).toList(),
                    "continuation authority must bind latest terminal observations, not a stale result");
            assertTrue(bound.stream().allMatch(source ->
                    "EVENT_OBSERVATION".equals(source.kind())));
        }
    }

    private static String singleCallJson(String id, String name) throws Exception {
        var root = JSON.createObjectNode();
        root.put("id", id).put("type", "function");
        root.putObject("function").put("name", name).put("arguments", "{}");
        return JSON.writeValueAsString(root);
    }

    private static String batchJson(List<String> ids) throws Exception {
        ArrayNode array = JSON.createArrayNode();
        for (String id : ids) {
            var root = array.addObject();
            root.put("id", id).put("type", "function");
            root.putObject("function").put("name", "write_file")
                    .put("arguments", "{}");
        }
        return JSON.writeValueAsString(array);
    }
}
