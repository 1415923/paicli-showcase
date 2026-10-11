package com.paicli.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.state.PlanCompletionModels.CompletionRuleType;
import com.paicli.state.PlanCompletionModels.EffectClass;
import com.paicli.state.PlanCompletionModels.EvidenceKind;
import com.paicli.state.StructuredPlanModels.FreshnessPolicy;
import com.paicli.state.StructuredPlanModels.Revision;
import com.paicli.state.StructuredPlanModels.StepDefinition;
import com.paicli.state.StructuredPlanModels.ToolRequirement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.List;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class PlanToolRequirementPolicyTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void exactReadSelectorIsCanonicalAndProviderCannotSupplyDerivedFields() throws Exception {
        var selector = PlanToolRequirementPolicy.normalize("read_file", JSON.readTree("""
                {"kind":"EXACT_PATH","path":"src/Main.java"}
                """));
        assertEquals("EXACT_PATH", selector.kind().name());
        assertEquals("{\"path\":\"src/Main.java\",\"maxLines\":null,"
                + "\"includeInternal\":false}", selector.canonicalJson());
        assertEquals(PlanToolRequirementPolicy.digest(selector.canonicalJson()),
                selector.digest());
        assertThrows(IllegalArgumentException.class, () ->
                PlanToolRequirementPolicy.normalize("read_file", JSON.readTree("""
                        {"kind":"EXACT_PATH","path":"src/Main.java",
                         "canonicalJson":"provider-owned","digest":"deadbeef"}
                        """)));
    }

    @Test
    void registeredToolKindEffectAndExactArgumentsMustAgree() throws Exception {
        var grep = PlanToolRequirementPolicy.normalize("grep", JSON.readTree("""
                {"kind":"EXACT_GREP","root":"src","pattern":"TODO|FIXME"}
                """));
        assertDoesNotThrow(() -> new ToolRequirement("grep-source", 0, "grep",
                EffectClass.READ, grep, 1, 1, FreshnessPolicy.RESUME_REUSE_ONLY));
        assertThrows(IllegalArgumentException.class, () -> new ToolRequirement(
                "grep-write", 0, "grep", EffectClass.WRITE, grep, 1, 1,
                FreshnessPolicy.RESUME_REUSE_ONLY));
        assertThrows(IllegalArgumentException.class, () ->
                PlanToolRequirementPolicy.normalize("apply_patch", JSON.readTree("""
                        {"kind":"EXACT_PATH_SET","paths":["src/Main.java"]}
                        """)));
        assertThrows(IllegalArgumentException.class, () ->
                PlanToolRequirementPolicy.normalize("read_file", JSON.readTree("""
                        {"kind":"EXACT_PATH","path":"../outside.txt"}
                        """)));
        assertThrows(IllegalArgumentException.class, () ->
                PlanToolRequirementPolicy.normalize("write_file", JSON.readTree("""
                        {"kind":"EXACT_PATH","path":"out.txt","overwrite":"yes"}
                        """)));
    }

    @Test
    void requirementCardinalityIdentityAndStepOrderingFailClosed() throws Exception {
        var selector = PlanToolRequirementPolicy.normalize("read_file", JSON.readTree("""
                {"kind":"EXACT_PATH","path":"README.md"}
                """));
        assertThrows(IllegalArgumentException.class, () -> new ToolRequirement(
                "bad id", 0, "read_file", EffectClass.READ, selector, 1, 1,
                FreshnessPolicy.RESUME_REUSE_ONLY));
        assertThrows(IllegalArgumentException.class, () -> new ToolRequirement(
                "negative", -1, "read_file", EffectClass.READ, selector, 1, 1,
                FreshnessPolicy.RESUME_REUSE_ONLY));
        assertThrows(IllegalArgumentException.class, () -> new ToolRequirement(
                "zero-min", 0, "read_file", EffectClass.READ, selector, 0, 1,
                FreshnessPolicy.RESUME_REUSE_ONLY));
        assertThrows(IllegalArgumentException.class, () -> new ToolRequirement(
                "over-max", 0, "read_file", EffectClass.READ, selector, 2, 1,
                FreshnessPolicy.RESUME_REUSE_ONLY));
        assertThrows(IllegalArgumentException.class, () -> new ToolRequirement(
                "unbounded", 0, "read_file", EffectClass.READ, selector, 1, 65,
                FreshnessPolicy.RESUME_REUSE_ONLY));

        ToolRequirement first = new ToolRequirement("read-one", 0, "read_file",
                EffectClass.READ, selector, 1, 1, FreshnessPolicy.RESUME_REUSE_ONLY);
        ToolRequirement duplicateOrder = new ToolRequirement("read-two", 0, "read_file",
                EffectClass.READ, selector, 1, 1, FreshnessPolicy.RESUME_REUSE_ONLY);
        assertThrows(IllegalArgumentException.class, () -> new StepDefinition(
                CanonicalId.newId(EntityFamily.PLAN_STEP),
                CanonicalId.newId(EntityFamily.PLAN_STEP), 0, "Inspect", "Read exact file",
                CompletionRuleType.ALL_REQUIRED_EVIDENCE, List.of(EvidenceKind.TOOL_CALL),
                EffectClass.READ, List.of(first, duplicateOrder), "a".repeat(64)));
    }

    @Test
    @DisplayName("SEC-01 selectors expose digest-safe public authority without write content")
    void everyRegisteredToolUsesOnlyItsFrozenAuthorityFields() throws Exception {
        var list = PlanToolRequirementPolicy.normalize("list_files", JSON.readTree("""
                {"kind":"EXACT_PATH","path":"src","includeInternal":true}
                """));
        assertEquals("{\"path\":\"src\",\"includeInternal\":true}",
                list.canonicalJson());

        var write = PlanToolRequirementPolicy.normalize("write_file", JSON.readTree("""
                {"kind":"EXACT_PATH","path":"out.txt","overwrite":true}
                """));
        assertEquals("{\"path\":\"out.txt\",\"overwrite\":true}",
                write.canonicalJson());
        assertThrows(IllegalArgumentException.class, () ->
                PlanToolRequirementPolicy.normalize("write_file", JSON.readTree("""
                        {"kind":"EXACT_PATH","path":"out.txt","overwrite":true,
                         "content":"must-not-enter-authority"}
                        """)));

        var command = PlanToolRequirementPolicy.normalize("run_command", JSON.readTree("""
                {"kind":"EXACT_COMMAND","command":"  mvn   -q   test  "}
                """));
        assertEquals("{\"command\":\"mvn   -q   test\"}", command.canonicalJson());
        var compactCommand = PlanToolRequirementPolicy.normalize("run_command", JSON.readTree("""
                {"kind":"EXACT_COMMAND","command":"mvn -q test"}
                """));
        assertNotEquals(command.digest(), compactCommand.digest(),
                "internal whitespace and quoting remain exact command authority");
        var crlf = PlanToolRequirementPolicy.normalize("run_command", JSON.readTree(
                "{\"kind\":\"EXACT_COMMAND\",\"command\":\"echo a\\r\\necho b\"}"));
        var lf = PlanToolRequirementPolicy.normalize("run_command", JSON.readTree(
                "{\"kind\":\"EXACT_COMMAND\",\"command\":\"echo a\\necho b\"}"));
        assertEquals(crlf.digest(), lf.digest());

        var diff = PlanToolRequirementPolicy.normalize("git_diff", JSON.readTree("""
                {"kind":"EXACT_GIT_DIFF","subcommand":"diff",
                 "args":["--","src/A.java","src/B.java"]}
                """));
        var reversed = PlanToolRequirementPolicy.normalize("git_diff", JSON.readTree("""
                {"kind":"EXACT_GIT_DIFF","subcommand":"diff",
                 "args":["--","src/B.java","src/A.java"]}
                """));
        assertNotEquals(diff.digest(), reversed.digest(),
                "ordered normalized git arguments are exact authority");

        assertThrows(IllegalArgumentException.class, () ->
                PlanToolRequirementPolicy.normalize("read_file", JSON.readTree("""
                        {"kind":"EXACT_PATH_SET","paths":["README.md"]}
                        """)));
    }

    @Test
    void requirementIdIsUniqueAcrossTheWholeRevision() throws Exception {
        var firstSelector = PlanToolRequirementPolicy.normalize("read_file", JSON.readTree("""
                {"kind":"EXACT_PATH","path":"one.txt"}
                """));
        var secondSelector = PlanToolRequirementPolicy.normalize("read_file", JSON.readTree("""
                {"kind":"EXACT_PATH","path":"two.txt"}
                """));
        ToolRequirement firstRequirement = new ToolRequirement("revision-wide-id", 0,
                "read_file", EffectClass.READ, firstSelector, 1, 1,
                FreshnessPolicy.RESUME_REUSE_ONLY);
        ToolRequirement secondRequirement = new ToolRequirement("revision-wide-id", 0,
                "read_file", EffectClass.READ, secondSelector, 1, 1,
                FreshnessPolicy.RESUME_REUSE_ONLY);
        StepDefinition first = step(0, firstRequirement);
        StepDefinition second = step(1, secondRequirement);

        assertThrows(IllegalArgumentException.class, () -> new Revision(
                CanonicalId.newId(EntityFamily.PLAN), CanonicalId.newId(EntityFamily.TASK),
                1, "goal", "accept", "", "", "verify", "0".repeat(64),
                "MODEL", "test", CanonicalId.newId(EntityFamily.RUN),
                Instant.parse("2026-08-02T00:00:00Z"), List.of(first, second),
                List.of(), List.of(), Revision.CANONICAL_SCHEMA_V3));
    }

    private static StepDefinition step(int order, ToolRequirement requirement) {
        return new StepDefinition(CanonicalId.newId(EntityFamily.PLAN_STEP),
                CanonicalId.newId(EntityFamily.PLAN_STEP), order, "Step " + order,
                "Execute exact requirement", CompletionRuleType.ALL_REQUIRED_EVIDENCE,
                List.of(EvidenceKind.TOOL_CALL), EffectClass.READ,
                List.of(requirement), "a".repeat(64));
    }
}
