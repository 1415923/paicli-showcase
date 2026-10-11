package com.paicli.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.agent.SafePreflightRejectionDiagnostic;
import com.paicli.runtime.PlanCompatibilityObserver;
import com.paicli.state.StateStore;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** V3-only closed projections; no raw Plan content or Tool arguments leave production state. */
final class FinalSourceV3EvidenceProjection implements PlanCompatibilityObserver {
    static final String PLAN_SCHEMA = "final-source-v3-plan-stage-evidence-v1";
    static final String PREFLIGHT_SCHEMA = "final-source-v3-safe-preflight-projection-v1";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path ledger;
    private final Path allowedRoot;
    private final String logicalRunId;
    private final String runtime;
    private final List<Map<String, Object>> planFacts = new ArrayList<>();
    private int eventOrdinal;

    FinalSourceV3EvidenceProjection(Path ledger, Path allowedRoot,
            String logicalRunId, String runtime) {
        this.ledger = ledger.toAbsolutePath().normalize();
        this.allowedRoot = allowedRoot.toAbsolutePath().normalize();
        if (!this.ledger.startsWith(this.allowedRoot) || this.ledger.equals(this.allowedRoot)) {
            throw new IllegalArgumentException("V3 Plan ledger escaped evidence root");
        }
        this.logicalRunId = safe(logicalRunId);
        this.runtime = safe(runtime);
        if (Files.exists(this.ledger)) {
            throw new IllegalArgumentException("V3 Plan ledger must be fresh");
        }
    }

    @Override
    public synchronized void observed(Fact fact) {
        Map<String, Object> value = projectPlan(fact);
        value.put("schemaVersion", PLAN_SCHEMA);
        value.put("logicalRunId", logicalRunId);
        value.put("runtime", runtime);
        value.put("eventOrdinal", ++eventOrdinal);
        append(value);
        planFacts.add(Map.copyOf(value));
    }

    synchronized List<Map<String, Object>> planFacts() {
        return List.copyOf(planFacts);
    }

    static Map<String, Object> projectPlan(Fact fact) {
        Objects.requireNonNull(fact);
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("wireSchemaStatus", switch (fact.planParseStatus()) {
            case ACCEPTED -> "WIRE_SCHEMA_ACCEPTED";
            case SCHEMA_INVALID -> "WIRE_SCHEMA_INVALID";
            case CONTENT_BOUNDS_INVALID -> "WIRE_CONTENT_BOUNDS_INVALID";
            case PARSE_INVALID -> "WIRE_PARSE_INVALID";
            case NOT_APPLICABLE -> "NOT_APPLICABLE_NO_STRUCTURED_PLAN_STAGE";
        });
        value.put("planSchemaVersion", safe(fact.schemaVersion()));
        value.put("unknownFieldCount", fact.unknownFieldCount());
        value.put("missingRequiredFieldCount", fact.missingRequiredFieldCount());
        value.put("normalizationCount", fact.normalizationCount());
        value.put("repairAttemptCount", fact.repairAttemptCount());
        value.put("terminalReason", "ACCEPTED".equals(fact.terminalReason())
                ? "WIRE_SCHEMA_ACCEPTED" : safe(fact.terminalReason()));
        value.put("requiredFields", safeList(fact.requiredFields()));
        value.put("missingRequiredFields", safeList(fact.missingRequiredFields()));
        value.put("unknownFields", safeList(fact.unknownFields()));
        value.put("validationErrors", safeList(fact.validationErrors()));
        return value;
    }

    static String planningStageOutcome(PaiCliBenchmarkTreatment.RunResult run) {
        return planningStageOutcome(run.summary(), run.toolCalls(),
                run.structuredVerificationCount(), run.taskCompleted());
    }

    static String planningStageOutcome(String typedSummary, int toolCalls,
            int structuredVerificationCount, boolean taskCompleted) {
        String summary = typedSummary == null ? "" : typedSummary;
        if (summary.contains("STRUCTURED_PLAN_SCHEMA_MAPPING_FAILED")) {
            return "DOMAIN_MAPPING_FAILED";
        }
        if (summary.contains("PLAN_PROPOSAL_REJECTED")) {
            return "AUTHORITY_PROPOSAL_REJECTED";
        }
        if (toolCalls > 0 || structuredVerificationCount > 0 || taskCompleted) {
            return "PLAN_APPROVED";
        }
        return "OTHER_TYPED_PLANNING_OUTCOME";
    }

    static List<Map<String, Object>> safePreflight(Path workspace, String taskId)
            throws Exception {
        List<Map<String, Object>> result = new ArrayList<>();
        try (StateStore state = new StateStore(workspace)) {
            for (var message : state.events.findMessages(taskId)) {
                var parsed = SafePreflightRejectionDiagnostic.parse(message.content());
                if (parsed.isEmpty()) continue;
                SafePreflightRejectionDiagnostic fact = parsed.orElseThrow();
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("taskId", safe(taskId));
                value.put("runId", safe(fact.runId()));
                value.put("toolName", safe(fact.toolName()));
                value.put("batchOrdinal", fact.batchOrdinal());
                value.put("preflightFailureKind", fact.failureKind().name());
                value.put("safeReason", switch (fact.safeReason()) {
                    case ARGUMENT_SCHEMA_REJECTED -> "ARGUMENT_SCHEMA_REJECTED";
                    case POLICY_REJECTED, PROTECTED_CONTROL_PATH_REJECTED -> "POLICY_REJECTED";
                    case APPROVAL_REQUIRED -> "APPROVAL_REQUIRED";
                    case AUTHORITY_REJECTED -> "AUTHORITY_REJECTED";
                    case INTEGRITY_REJECTED -> "INTEGRITY_REJECTED";
                    default -> "OTHER_SAFE_REJECTION";
                });
                value.put("safePreflightCode", safe(fact.preflightCode()));
                result.add(Map.copyOf(value));
            }
        }
        return List.copyOf(result);
    }

    static List<String> continuityTerminalReasons(Path workspace, String taskId)
            throws Exception {
        try (StateStore state = new StateStore(workspace)) {
            return state.taskContinuity.findByTask(taskId).stream()
                    .map(segment -> segment.terminalReason())
                    .filter(Objects::nonNull).map(FinalSourceV3EvidenceProjection::safe)
                    .toList();
        }
    }

    private void append(Map<String, Object> value) {
        try {
            Files.createDirectories(ledger.getParent());
            byte[] payload = (JSON.writeValueAsString(value) + "\n")
                    .getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(ledger, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.APPEND);
                 var ignored = channel.lock()) {
                channel.write(ByteBuffer.wrap(payload));
                channel.force(true);
            }
        } catch (Exception error) {
            throw new IllegalStateException("V3 Plan projection append failed", error);
        }
    }

    private static List<String> safeList(List<String> values) {
        return values.stream().map(FinalSourceV3EvidenceProjection::safe).toList();
    }

    private static String safe(String value) {
        if (value == null || value.isBlank() || value.length() > 512
                || !value.matches("[A-Za-z0-9_$.:/\\[\\]-]+")) {
            throw new IllegalArgumentException("unsafe V3 typed projection token");
        }
        return value;
    }
}
