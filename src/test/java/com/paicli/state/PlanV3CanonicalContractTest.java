package com.paicli.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.identity.CanonicalId;
import com.paicli.state.PlanCompletionModels.CompletionRuleType;
import com.paicli.state.PlanCompletionModels.EffectClass;
import com.paicli.state.PlanCompletionModels.EvidenceKind;
import com.paicli.state.StructuredPlanModels.FreshnessPolicy;
import com.paicli.state.StructuredPlanModels.Revision;
import com.paicli.state.StructuredPlanModels.StepDefinition;
import com.paicli.state.StructuredPlanModels.ToolRequirement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlanV3CanonicalContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final CanonicalId PLAN = id("pln", 1);
    private static final CanonicalId TASK = id("tsk", 2);
    private static final CanonicalId RUN = id("run", 3);
    private static final CanonicalId STEP = id("stp", 4);
    private static final CanonicalId LOGICAL = id("stp", 5);
    private static final Instant AT = Instant.parse("2026-08-02T00:00:00Z");

    @Test
    @DisplayName("V3-02 V2 canonical hash remains frozen")
    void frozenV2GoldenHashIsByteForByteUnchanged() {
        StepDefinition step = new StepDefinition(STEP, LOGICAL, 0, "one", "definition",
                CompletionRuleType.ALL_REQUIRED_EVIDENCE,
                List.of(EvidenceKind.TOOL_CALL, EvidenceKind.SNAPSHOT),
                EffectClass.GENERAL, "b".repeat(64));
        Revision v2 = revision(step, Revision.CANONICAL_SCHEMA_V2);

        assertEquals("1d662d872d4637fda0466bf0dce1dfc998fe5ebb1235c9351a61f094000cd7aa",
                PlanCanonicalizer.hash(v2));
    }

    @Test
    @DisplayName("V3-01 V3 requirements enter canonical hash")
    void V3HashBindsExactSelectorAndCardinality() throws Exception {
        Revision baseline = v3("README.md", 1, 1,
                FreshnessPolicy.RESUME_REUSE_ONLY);
        Revision changedPath = v3("CHANGELOG.md", 1, 1,
                FreshnessPolicy.RESUME_REUSE_ONLY);
        Revision changedCardinality = v3("README.md", 2, 2,
                FreshnessPolicy.FRESH_EXECUTION_REQUIRED);

        assertEquals(PlanCanonicalizer.hash(baseline), PlanCanonicalizer.hash(baseline));
        assertNotEquals(PlanCanonicalizer.hash(baseline), PlanCanonicalizer.hash(changedPath));
        assertNotEquals(PlanCanonicalizer.hash(baseline),
                PlanCanonicalizer.hash(changedCardinality));
        assertNotEquals(PlanCanonicalizer.hash(revision(
                new StepDefinition(STEP, LOGICAL, 0, "one", "definition",
                        CompletionRuleType.ALL_REQUIRED_EVIDENCE,
                        List.of(EvidenceKind.TOOL_CALL), EffectClass.READ,
                        "b".repeat(64)), Revision.CANONICAL_SCHEMA_V2)),
                PlanCanonicalizer.hash(baseline));
    }

    private static Revision v3(String path, int min, int max,
            FreshnessPolicy freshness) throws Exception {
        var selector = PlanToolRequirementPolicy.normalize("read_file", JSON.readTree(
                "{\"kind\":\"EXACT_PATH\",\"path\":\"" + path + "\"}"));
        ToolRequirement requirement = new ToolRequirement("read-exact", 0, "read_file",
                EffectClass.READ, selector, min, max, freshness);
        StepDefinition step = new StepDefinition(STEP, LOGICAL, 0, "one", "definition",
                CompletionRuleType.ALL_REQUIRED_EVIDENCE, List.of(EvidenceKind.TOOL_CALL),
                EffectClass.READ, List.of(requirement), "b".repeat(64));
        return revision(step, Revision.CANONICAL_SCHEMA_V3);
    }

    private static Revision revision(StepDefinition step, int schema) {
        return new Revision(PLAN, TASK, 1, "goal", "accept", "", "", "verify",
                "0".repeat(64), "MODEL", "test", RUN, AT,
                List.of(step), List.of(), List.of(), schema);
    }

    private static CanonicalId id(String prefix, int suffix) {
        return CanonicalId.parse(prefix + "_0000000000004000800000000000000" + suffix);
    }
}
