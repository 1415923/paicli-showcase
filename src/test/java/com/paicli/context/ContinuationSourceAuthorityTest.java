package com.paicli.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.context.ContextModels.*;
import com.paicli.model.AgentMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Deterministic rejection surface for exact latest-turn continuation authority. */
class ContinuationSourceAuthorityTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final DeterministicContextCompiler compiler = new DeterministicContextCompiler();

    @Test void completeSingleExecutedResultPathStillCompiles() throws Exception {
        List<ContextSource> sources = sources(List.of("call-1"), List.of("call-1"));
        assertDoesNotThrow(() -> compiler.compile(request(sources, List.of("observation-0"))));
    }

    @Test void missingLatestResultFailsClosed() throws Exception {
        List<ContextSource> sources = sources(List.of("call-a", "call-b"), List.of("call-a"));
        assertContextIntegrity(sources, List.of("observation-0"));
    }

    @Test void duplicateExpectedCallIdentityFailsClosed() throws Exception {
        List<ContextSource> sources = sources(List.of("call-a", "call-a"),
                List.of("call-a", "call-a"));
        assertContextIntegrity(sources, List.of("observation-0", "observation-1"));
    }

    @Test void mismatchedLatestResultIdentityFailsClosed() throws Exception {
        List<ContextSource> sources = sources(List.of("call-a", "call-b"),
                List.of("call-a", "wrong"));
        assertContextIntegrity(sources, List.of("observation-0", "observation-1"));
    }

    @Test void staleHistoricalResultClaimCannotReplaceLatestResults() throws Exception {
        List<ContextSource> sources = new ArrayList<>(sources(
                List.of("call-a", "call-b"), List.of("call-a", "call-b")));
        sources.add(source(75, 0, "TOOL_RESULT", "historical-result", "1",
                AgentMessage.Role.ASSISTANT, AgentMessage.MessageType.FINAL, null, null,
                "kind=TOOL_RESULT;status=COMPLETED"));
        assertContextIntegrity(sources, List.of("historical-result"));
    }

    @Test void compactedHistoricalLedgerCannotReplaceOrDisplaceLatestBinding() throws Exception {
        List<ContextSource> sources = new ArrayList<>(sources(
                List.of("call-a", "call-b"), List.of("call-a", "call-b")));
        sources.add(source(70, 0, "COMPACTED_LEDGER", "old-ledger", "1",
                AgentMessage.Role.USER, AgentMessage.MessageType.USER, null, null,
                "synthetic compacted historical facts"));
        assertDoesNotThrow(() -> compiler.compile(request(sources,
                List.of("observation-0", "observation-1"))));
    }

    private static void assertContextIntegrity(List<ContextSource> sources, List<String> claimed) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new DeterministicContextCompiler().compile(request(sources, claimed)));
        assertEquals("CONTEXT_SOURCE_INTEGRITY", failure.getMessage());
    }

    private static CompileRequest request(List<ContextSource> values, List<String> claimed) {
        List<ContextSource> ordered = values.stream().sorted(ContextSourceSet.ORDER).toList();
        Identity identity = new Identity("workspace",
                "thr_0123456789ab4def8abc0123456789ab",
                "tsk_0123456789ab4def8abc0123456789ab",
                "run_0123456789ab4def8abc0123456789ab",
                "gen_0123456789ab4def8abc0123456789ab",
                "ctx_0123456789ab4def8abc0123456789ab", "0".repeat(64), null, null);
        ContextInvocation invocation = new ContextInvocation(identity,
                OperationKind.TOOL_CONTINUATION, "2", "intent", claimed, null, null, null,
                "mock", "model", new CapabilityProfile("mock", 131_072,
                LimitProvenance.PROVIDER_REPORTED_LIMIT, 128, 32), false,
                DeterministicContextCompiler.COMPILER_VERSION,
                DeterministicContextCompiler.POLICY_VERSION);
        return new CompileRequest(invocation, new ContextSourceSet(ordered,
                DeterministicContextCompiler.sourceFingerprint(ordered)), List.of());
    }

    private static List<ContextSource> sources(List<String> expected, List<String> observed)
            throws Exception {
        var out = new ArrayList<ContextSource>();
        out.add(source(90, 0, "DURABLE_USER_INTENT", "intent", "0",
                AgentMessage.Role.USER, AgentMessage.MessageType.USER, null, null, "goal"));
        var calls = JSON.createArrayNode();
        for (String id : expected) {
            var call = calls.addObject();
            call.put("id", id).put("type", "function");
            call.putObject("function").put("name", "synthetic").put("arguments", "{}");
        }
        out.add(source(80, 1, "EVENT_ACTION", "action", "1",
                AgentMessage.Role.ASSISTANT, AgentMessage.MessageType.ACTION,
                expected.getFirst(), JSON.writeValueAsString(calls), "synthetic action"));
        for (int index = 0; index < observed.size(); index++) {
            out.add(source(80, index + 2L, "EVENT_OBSERVATION", "observation-" + index, "1",
                    AgentMessage.Role.ASSISTANT, AgentMessage.MessageType.OBSERVATION,
                    observed.get(index), null, "synthetic terminal result"));
        }
        return List.copyOf(out);
    }

    private static ContextSource source(int priority, long sequence, String kind, String id,
            String revision, AgentMessage.Role role, AgentMessage.MessageType type,
            String toolCallId, String toolCallJson, String payload) {
        String hash = DeterministicContextCompiler.hash(payload);
        return new ContextSource(priority, sequence, SourceClass.COMPACTABLE_DURABLE_HISTORY,
                kind, id, revision, "SYNTHETIC", Scope.RUN_SCOPED,
                Compactability.NON_COMPACTABLE, PayloadMode.INLINE_SANITIZED,
                DeterministicContextCompiler.RENDERER_VERSION, payload, hash,
                PrivacyClass.INTERNAL, DeterministicContextCompiler.MAX_SEGMENT_BYTES, true,
                role, type, toolCallId, toolCallJson,
                List.of(new SourceReference("CONTEXT_INLINE", id, revision, hash, 0)));
    }
}
