package com.paicli.runtime;

import com.paicli.model.AgentMessage;
import com.paicli.model.ModelResponse;
import com.paicli.provider.ModelProvider;
import com.paicli.context.ContextModels.OperationKind;
import com.paicli.context.ContextualModelProvider;
import com.paicli.state.TaskPlanStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.state.StructuredPlanModels.Dependency;
import com.paicli.state.StructuredPlanModels.Lineage;
import com.paicli.state.StructuredPlanModels.LineageRelationship;
import com.paicli.state.StructuredPlanModels.Revision;
import com.paicli.state.StructuredPlanModels.StepDefinition;
import com.paicli.state.PlanCanonicalizer;
import com.paicli.state.PlanContentBounds;
import com.paicli.state.PlanCompletionProfilePolicy;
import com.paicli.state.PlanCompletionModels.CompletionRuleType;
import com.paicli.state.PlanCompletionModels.EffectClass;
import com.paicli.state.PlanCompletionModels.EvidenceKind;
import com.paicli.state.PlanToolRequirementPolicy;
import com.paicli.state.StructuredPlanModels.FreshnessPolicy;
import com.paicli.state.StructuredPlanModels.ToolRequirement;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** Generates and persists a tool-free coding plan. */
public final class PlanService {

    private static final String MODEL_ERROR_PREFIX = "[MODEL_ERROR]";
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private final ModelProvider modelProvider;
    private TaskProviderBudgetGate taskProviderBudgetGate=TaskProviderBudgetGate.ALLOW_ALL;
    private final TaskPlanStore planStore;
    private final PersistenceSanitizer sanitizer;
    private final int maxPlanBytes;
    private final Supplier<String> planIdSupplier;
    private final PlanCompatibilityObserver compatibilityObserver;
    private PlanRuntimeMode runtimeMode = PlanRuntimeMode.STRICT_INTERNAL;

    public PlanService(ModelProvider modelProvider, TaskPlanStore planStore) {
        this(modelProvider, planStore, new PersistenceSanitizer(),
                PersistenceSanitizer.DEFAULT_MAX_TEXT_BYTES, () -> UUID.randomUUID().toString(),
                PlanCompatibilityObserver.NOOP);
    }

    /** Explicit-continuity-only Provider admission gate; disabled callers retain legacy behavior. */
    public PlanService bindTaskProviderBudgetGate(TaskProviderBudgetGate gate) {
        Objects.requireNonNull(gate,"gate");
        if(taskProviderBudgetGate!=TaskProviderBudgetGate.ALLOW_ALL
                &&taskProviderBudgetGate!=gate)
            throw new IllegalStateException("Task Provider budget gate is already bound");
        taskProviderBudgetGate=gate;
        return this;
    }

    public PlanService(ModelProvider modelProvider, TaskPlanStore planStore,
            PlanCompatibilityObserver compatibilityObserver) {
        this(modelProvider, planStore, new PersistenceSanitizer(),
                PersistenceSanitizer.DEFAULT_MAX_TEXT_BYTES, () -> UUID.randomUUID().toString(),
                compatibilityObserver);
    }

    public PlanService(ModelProvider modelProvider, TaskPlanStore planStore,
                       PersistenceSanitizer sanitizer, int maxPlanBytes) {
        this(modelProvider, planStore, sanitizer, maxPlanBytes,
                () -> UUID.randomUUID().toString(), PlanCompatibilityObserver.NOOP);
    }

    PlanService(ModelProvider modelProvider, TaskPlanStore planStore,
                PersistenceSanitizer sanitizer, int maxPlanBytes,
                Supplier<String> planIdSupplier) {
        this(modelProvider, planStore, sanitizer, maxPlanBytes, planIdSupplier,
                PlanCompatibilityObserver.NOOP);
    }

    PlanService(ModelProvider modelProvider, TaskPlanStore planStore,
                PersistenceSanitizer sanitizer, int maxPlanBytes,
                Supplier<String> planIdSupplier,
                PlanCompatibilityObserver compatibilityObserver) {
        this.modelProvider = Objects.requireNonNull(modelProvider, "modelProvider");
        this.planStore = Objects.requireNonNull(planStore, "planStore");
        this.sanitizer = Objects.requireNonNull(sanitizer, "sanitizer");
        if (maxPlanBytes <= 0) {
            throw new IllegalArgumentException("maxPlanBytes must be positive");
        }
        this.maxPlanBytes = maxPlanBytes;
        this.planIdSupplier = Objects.requireNonNull(planIdSupplier, "planIdSupplier");
        this.compatibilityObserver = Objects.requireNonNull(compatibilityObserver,
                "compatibilityObserver");
    }

    /** Binds the public/default non-authoritative Plan wire before a CodingTaskRunner starts. */
    PlanService advisoryPlanMode() {
        runtimeMode = PlanRuntimeMode.DEFAULT_ADVISORY;
        return this;
    }

    /** Package-private test seam for the unfinished Strict authority foundation. */
    PlanService strictPlanModeForTesting() {
        runtimeMode = PlanRuntimeMode.STRICT_INTERNAL;
        return this;
    }

    public PlanResult generate(String taskId, String runId, String goal) throws SQLException {
        return generate(taskId, runId, goal, "");
    }

    public PlanResult generate(String taskId, String runId, String goal,
                               String skillContext) throws SQLException {
        return generate(taskId,runId,goal,skillContext,null);
    }

    /** Generates a non-authoritative structured candidate; the caller persists it fenced. */
    public Revision generateStructured(CanonicalId taskId, CanonicalId runId, String goal,
            String skillContext, FencedExecutionGuard executionGuard, int revisionNumber,
            Instant createdAt) {
        return generateStructured(taskId, runId, goal, skillContext, executionGuard,
                revisionNumber, createdAt, null);
    }

    /** Generates a candidate whose completion profile matches the current Run capabilities. */
    public Revision generateStructured(CanonicalId taskId, CanonicalId runId, String goal,
            String skillContext, FencedExecutionGuard executionGuard, int revisionNumber,
            Instant createdAt, PlanCompletionProfilePolicy.Capabilities capabilities) {
        return generateStructured(taskId,runId,goal,skillContext,executionGuard,revisionNumber,
                1,createdAt,capabilities);
    }

    /**
     * Generates one explicitly identified planning attempt. The attempt ordinal changes Provider
     * operation identity without changing the immutable accepted Plan revision number.
     */
    public Revision generateStructured(CanonicalId taskId, CanonicalId runId, String goal,
            String skillContext, FencedExecutionGuard executionGuard, int revisionNumber,
            int planningAttemptOrdinal, Instant createdAt,
            PlanCompletionProfilePolicy.Capabilities capabilities) {
        Objects.requireNonNull(taskId); Objects.requireNonNull(runId);
        if (taskId.family() != EntityFamily.TASK || runId.family() != EntityFamily.RUN
                || revisionNumber < 1 || planningAttemptOrdinal < 1 || createdAt == null) {
            throw new IllegalArgumentException(
                    "Typed Task/Run, revision and planning-attempt ordinals are required");
        }
        String planningPrompt = structuredPlanningPrompt(goal, skillContext, capabilities);
        String content = requireFinalPlan(requestStructuredPlan(
                List.of(AgentMessage.userGoal(planningPrompt)), executionGuard,
                planOperationIdentity(revisionNumber,planningAttemptOrdinal,null),
                "PLAN_PROVIDER"));
        ParsedStructuredPlan parsed = parseStructuredPlan(content, planningPrompt,
                executionGuard, revisionNumber,planningAttemptOrdinal);
        Revision candidate = validateStructuredCandidate(mapRevision(parsed.root(), taskId, runId,
                goal, revisionNumber, createdAt, "MODEL", "R1C_A_STRUCTURED_PLAN"));
        if (capabilities == null) return candidate;
        PlanCompletionProfilePolicy.Validation validation =
                PlanCompletionProfilePolicy.validate(candidate, capabilities);
        if (validation.producible()) return candidate;
        if (parsed.repairRequestUsed()) throw completionProfileUnproducible(validation.errors());

        String repairPrompt = completionProfileRepairPrompt(validation.errors(), capabilities);
        String repairedContent = requireFinalPlan(requestStructuredPlan(List.of(
                        AgentMessage.userGoal(planningPrompt), AgentMessage.userGoal(repairPrompt)),
                executionGuard, planOperationIdentity(revisionNumber,planningAttemptOrdinal,
                        "profile-repair"),
                "PLAN_COMPLETION_PROFILE_REPAIR"));
        final Revision repairedCandidate;
        StructuredPlanResponseParser profileParser = new StructuredPlanResponseParser(runtimeMode);
        try {
            JsonNode repairedRoot = profileParser.parse(repairedContent, maxPlanBytes);
            observeCompatibility(profileParser, repairedContent,
                    PlanCompatibilityObserver.ParseStatus.ACCEPTED, 1,
                    "ACCEPTED_AFTER_COMPLETION_PROFILE_REPAIR", List.of());
            repairedCandidate = validateStructuredCandidate(mapRevision(repairedRoot, taskId, runId,
                    goal, revisionNumber, createdAt, "MODEL",
                    "R1C_A_STRUCTURED_PLAN_PROFILE_REPAIR"));
        } catch (StructuredPlanResponseParser.ParseFailure | PlanGenerationException error) {
            if (error instanceof StructuredPlanResponseParser.ParseFailure parseFailure) {
                observeCompatibility(profileParser, repairedContent, status(parseFailure), 1,
                        "COMPLETION_PROFILE_REPAIR_PARSE_FAILED", errors(parseFailure));
            }
            throw completionProfileUnproducible(List.of("REPAIRED_CANDIDATE_INVALID"));
        }
        PlanCompletionProfilePolicy.Validation repairedValidation =
                PlanCompletionProfilePolicy.validate(repairedCandidate, capabilities);
        if (!repairedValidation.producible()) {
            throw completionProfileUnproducible(repairedValidation.errors());
        }
        return repairedCandidate;
    }

    private Revision validateStructuredCandidate(Revision candidate) {
        PlanContentBounds.Validation bounds = PlanContentBounds.validate(candidate);
        if (!bounds.valid()) {
            throw new PlanGenerationException("PLAN_CONTENT_BOUNDS_VIOLATION: "
                    + String.join(", ", bounds.errors().stream().limit(8).toList()));
        }
        int expectedSchema = runtimeMode == PlanRuntimeMode.DEFAULT_ADVISORY
                ? Revision.CANONICAL_SCHEMA_V2 : Revision.CANONICAL_SCHEMA_V3;
        if (candidate.canonicalSchemaVersion() != expectedSchema) {
            throw new PlanGenerationException("STRUCTURED_PLAN_V3_REQUIREMENT_INVALID");
        }
        if (runtimeMode == PlanRuntimeMode.DEFAULT_ADVISORY) {
            if (candidate.steps().stream().anyMatch(
                    step -> !step.toolRequirements().isEmpty())) {
                throw new PlanGenerationException("ADVISORY_PLAN_CONTAINS_TOOL_AUTHORITY");
            }
            return candidate;
        }
        for (StepDefinition step : candidate.steps()) {
            boolean toolEvidence = step.requiredEvidenceKinds().contains(EvidenceKind.TOOL_CALL);
            if (toolEvidence != !step.toolRequirements().isEmpty()) {
                throw new PlanGenerationException("STRUCTURED_PLAN_V3_REQUIREMENT_INVALID");
            }
            try {
                for (ToolRequirement requirement : step.toolRequirements()) {
                    PlanToolRequirementPolicy.validate(requirement.toolName(),
                            requirement.effectClass(), requirement.authoritySelector());
                }
            } catch (IllegalArgumentException error) {
                throw new PlanGenerationException("STRUCTURED_PLAN_V3_REQUIREMENT_INVALID", error);
            }
        }
        return candidate;
    }

    private static PlanGenerationException completionProfileUnproducible(List<String> errors) {
        return new PlanGenerationException("COMPLETION_PROFILE_UNPRODUCIBLE: "
                + String.join(", ", errors.stream().limit(8).toList()));
    }

    private Revision mapRevision(JsonNode root, CanonicalId taskId, CanonicalId runId,
            String goal, int revisionNumber, Instant createdAt, String sourceType,
            String provenance) {
        try {
            CanonicalId planId = CanonicalId.newId(EntityFamily.PLAN);
            List<StepDefinition> steps = new ArrayList<>();
            List<CanonicalId> ids = new ArrayList<>();
            for (int i = 0; i < root.path("steps").size(); i++) ids.add(CanonicalId.newId(EntityFamily.PLAN_STEP));
            for (int i = 0; i < root.path("steps").size(); i++) {
                JsonNode step = root.path("steps").get(i);
                String title = requiredText(step, "title");
                String definition = requiredText(step, "definition");
                boolean advisory = runtimeMode == PlanRuntimeMode.DEFAULT_ADVISORY;
                CompletionRuleType completionRule = advisory
                        ? CompletionRuleType.ALL_REQUIRED_EVIDENCE
                        : enumValue(step, "completionRule", CompletionRuleType.class);
                EffectClass effectClass = advisory ? EffectClass.GENERAL
                        : enumValue(step, "effectClass", EffectClass.class);
                List<EvidenceKind> evidenceKinds = advisory ? List.of(EvidenceKind.TOOL_CALL)
                        : enumArray(step, "requiredEvidenceKinds", EvidenceKind.class);
                List<ToolRequirement> requirements = advisory ? List.of()
                        : toolRequirements(step);
                String requirementMaterial = requirements.stream().map(requirement -> String.join("\n",
                        requirement.requirementId(), Integer.toString(requirement.requirementOrder()),
                        requirement.toolName(), requirement.effectClass().name(),
                        requirement.authoritySelector().kind().name(),
                        requirement.authoritySelector().canonicalJson(),
                        requirement.authoritySelector().digest(),
                        Integer.toString(requirement.minSuccesses()),
                        Integer.toString(requirement.maxOccurrences()),
                        requirement.freshnessPolicy().name())).collect(
                                java.util.stream.Collectors.joining("\n--\n"));
                steps.add(new StepDefinition(ids.get(i), CanonicalId.newId(EntityFamily.PLAN_STEP),
                        i, title, definition, completionRule, evidenceKinds, effectClass,
                        requirements,
                        sha(title + "\n" + definition + "\n" + completionRule + "\n"
                                + effectClass + "\n" + evidenceKinds + "\n" + requirementMaterial)));
            }
            List<Dependency> dependencies = new ArrayList<>();
            for (int i = 0; i < root.path("steps").size(); i++) {
                JsonNode refs = root.path("steps").get(i).path("dependsOn");
                if (!refs.isMissingNode() && !refs.isArray()) throw new PlanGenerationException("dependsOn must be an array");
                for (JsonNode ref : refs) {
                    int index = ref.asInt(-1);
                    if (index < 0 || index >= ids.size()) throw new PlanGenerationException("Invalid Plan dependency index");
                    dependencies.add(new Dependency(ids.get(i), ids.get(index)));
                }
            }
            int canonicalSchemaVersion = runtimeMode == PlanRuntimeMode.DEFAULT_ADVISORY
                    ? Revision.CANONICAL_SCHEMA_V2 : Revision.CANONICAL_SCHEMA_V3;
            String modelProvenance = runtimeMode == PlanRuntimeMode.DEFAULT_ADVISORY
                    ? provenance + ":ADVISORY_WIRE_V1" : provenance;
            Revision candidate = new Revision(planId, taskId, revisionNumber, goal,
                    text(root, "acceptanceCriteria"), text(root, "assumptions"), text(root, "risks"),
                    text(root, "verificationStrategy"), "0".repeat(64), sourceType,
                    modelProvenance, runId, createdAt, steps, dependencies, List.of(),
                    canonicalSchemaVersion);
            return new Revision(planId, taskId, revisionNumber, goal, candidate.acceptanceCriteria(),
                    candidate.assumptions(), candidate.risks(), candidate.verificationStrategy(),
                    PlanCanonicalizer.hash(candidate), candidate.sourceType(), candidate.provenance(),
                    runId, createdAt, steps, dependencies, List.of(), canonicalSchemaVersion);
        } catch (PlanGenerationException error) { throw error; }
        catch (Exception error) {
            throw new PlanGenerationException(
                    "STRUCTURED_PLAN_SCHEMA_MAPPING_FAILED", error);
        }
    }

    public static String render(Revision revision) {
        StringBuilder text = new StringBuilder(revision.goal()).append("\n");
        for (StepDefinition step : revision.steps()) {
            text.append(step.order() + 1).append(". ").append(step.title()).append(": ")
                    .append(step.definition()).append("\n");
        }
        return text.toString().strip();
    }

    /**
     * Generates a genuinely new Structured Plan for material Steering. The previous
     * executable steps are context only; generated steps receive new logical identity.
     */
    public Revision generateSteeredStructured(Revision source,CanonicalId steeringId,
            String steeringContent,
            String skillContext,FencedExecutionGuard executionGuard,int revisionNumber,
            Instant createdAt){
        return generateSteeredStructured(source, steeringId, steeringContent, skillContext,
                executionGuard, revisionNumber, createdAt, null);
    }

    public Revision generateSteeredStructured(Revision source,CanonicalId steeringId,
            String steeringContent, String skillContext,FencedExecutionGuard executionGuard,
            int revisionNumber, Instant createdAt,
            PlanCompletionProfilePolicy.Capabilities capabilities){
        Objects.requireNonNull(source,"source");
        if(steeringId==null||steeringId.family()!=EntityFamily.STEERING)
            throw new IllegalArgumentException("Typed Steering identity is required");
        String steering=sanitizer.sanitizeAndTruncate(steeringContent,maxPlanBytes);
        if(steering==null||steering.isBlank())
            throw new PlanGenerationException("Material Steering content is empty");
        String updatedGoal=source.goal()+"\nMaterial Steering: "+steering;
        String planningGoal=updatedGoal+"""

                Previous approved Plan (context only; do not copy its executable steps):
                %s

                Produce a new executable Structured Plan whose Step Definitions implement
                the updated goal. Do not preserve an old Step unless it is independently
                required by the updated goal.
                """.formatted(render(source));
        CanonicalId currentRun=executionGuard.current().runId();
        Revision generated=generateStructured(source.taskId(),currentRun,
                planningGoal,skillContext,executionGuard,revisionNumber,createdAt,capabilities);
        String provenance="R1D_STEERING:"+steeringId.value();
        List<Lineage> lineage=new ArrayList<>();
        for(StepDefinition step:generated.steps())lineage.add(new Lineage(
                "lineage-"+generated.planRevisionId().value().substring(4)+"-added-"+step.order(),
                null,null,generated.planRevisionId(),step.stepRevisionId(),
                LineageRelationship.ADDED,provenance,createdAt));
        for(StepDefinition step:source.steps())lineage.add(new Lineage(
                "lineage-"+generated.planRevisionId().value().substring(4)+"-removed-"+step.order(),
                source.planRevisionId(),step.stepRevisionId(),generated.planRevisionId(),null,
                LineageRelationship.REMOVED,provenance,createdAt));
        Revision draft0=new Revision(generated.planRevisionId(),source.taskId(),revisionNumber,
                updatedGoal,generated.acceptanceCriteria(),generated.assumptions(),generated.risks(),
                generated.verificationStrategy(),"0".repeat(64),"STEERING",provenance,
                currentRun,createdAt,generated.steps(),generated.dependencies(),lineage,
                generated.canonicalSchemaVersion());
        return new Revision(draft0.planRevisionId(),draft0.taskId(),draft0.revisionNumber(),
                draft0.goal(),draft0.acceptanceCriteria(),draft0.assumptions(),draft0.risks(),
                draft0.verificationStrategy(),PlanCanonicalizer.hash(draft0),draft0.sourceType(),
                draft0.provenance(),draft0.createdByRunId(),draft0.createdAt(),draft0.steps(),
                draft0.dependencies(),draft0.lineage(),draft0.canonicalSchemaVersion());
    }

    public PlanResult generate(String taskId, String runId, String goal,
                               String skillContext,FencedExecutionGuard executionGuard)
            throws SQLException {
        requireText(taskId, "taskId");
        requireText(runId, "runId");
        requireText(goal, "goal");

        ModelResponse response;
        try {
            admitTaskProviderBudget(taskId,runId);
            if(executionGuard!=null)executionGuard.heartbeat("PLAN_PROVIDER_REQUEST");
            List<AgentMessage> prompt=List.of(AgentMessage.userGoal(planningPrompt(goal, skillContext)));
            if(modelProvider instanceof ContextualModelProvider contextual){
                if(executionGuard==null)throw new PlanGenerationException("CONTEXT_INVOCATION_REQUIRED");
                response=contextual.chat(OperationKind.PLAN,"legacy-plan",prompt,List.of(),executionGuard);
            }else response=modelProvider.chat(prompt,List.of());
            if(executionGuard!=null)executionGuard.heartbeat("PLAN_PROVIDER_RESPONSE");
        } catch (RuntimeException e) {
            throw planningInvocationFailure(e);
        }

        String planText = requireFinalPlan(response);
        String persistedPlan = sanitizer.sanitizeAndTruncate(planText, maxPlanBytes);
        if (persistedPlan == null || persistedPlan.isBlank()) {
            throw new PlanGenerationException("Plan model returned empty content");
        }

        String planId = planIdSupplier.get();
        requireText(planId, "planId");
        if(executionGuard!=null)executionGuard.recordPlan(planId,persistedPlan);
        else planStore.recordGenerated(planId, runId, taskId, persistedPlan);
        return new PlanResult(planId, runId, taskId, persistedPlan);
    }

    private String requireFinalPlan(ModelResponse response) {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationToken.TaskCancellationException(false);
        }
        if (response == null) {
            throw new PlanGenerationException("Plan model returned no response");
        }
        if (response.type() == ModelResponse.ResponseType.TOOL_CALL) {
            throw new PlanGenerationException("Plan model requested a tool call");
        }
        if (response.type() == ModelResponse.ResponseType.PROVIDER_ERROR) {
            throw new PlanGenerationException(providerFailureMessage(
                    response.providerErrorKind()));
        }
        if (response.type() == ModelResponse.ResponseType.PROTOCOL_ERROR) {
            throw new PlanGenerationException(
                    "PROVIDER_MALFORMED_RESPONSE: planning response violated the provider protocol");
        }
        if (response.type() != ModelResponse.ResponseType.FINAL) {
            throw new PlanGenerationException("Plan model returned an unsupported response type");
        }
        String content = response.content();
        if (content == null || content.isBlank()) {
            throw new PlanGenerationException("Plan model returned empty content");
        }
        if (content.stripLeading().startsWith(MODEL_ERROR_PREFIX)) {
            throw new PlanGenerationException("Plan model returned MODEL_ERROR");
        }
        return content;
    }

    private ModelResponse requestStructuredPlan(List<AgentMessage> messages,
            FencedExecutionGuard executionGuard, String operationIdentity,
            String heartbeatPrefix) {
        try {
            admitTaskProviderBudget(executionGuard.current().taskId().value(),
                    executionGuard.current().runId().value());
            executionGuard.heartbeat(heartbeatPrefix + "_REQUEST");
            ModelResponse response = modelProvider instanceof ContextualModelProvider contextual
                    ? contextual.chat(OperationKind.PLAN, operationIdentity,
                            messages, List.of(), executionGuard)
                    : modelProvider.chat(messages, List.of());
            executionGuard.heartbeat(heartbeatPrefix + "_RESPONSE");
            return response;
        } catch (RuntimeException error) {
            throw planningInvocationFailure(error);
        }
    }

    private ParsedStructuredPlan parseStructuredPlan(String content, String originalPrompt,
            FencedExecutionGuard executionGuard, int revisionNumber,
            int planningAttemptOrdinal) {
        StructuredPlanResponseParser parser = new StructuredPlanResponseParser(runtimeMode);
        try {
            JsonNode root = parser.parse(content, maxPlanBytes);
            observeCompatibility(parser, content, PlanCompatibilityObserver.ParseStatus.ACCEPTED,
                    0, "ACCEPTED", List.of());
            return new ParsedStructuredPlan(root, false);
        } catch (StructuredPlanResponseParser.SchemaFailure schemaFailure) {
            observeCompatibility(parser, content,
                    PlanCompatibilityObserver.ParseStatus.SCHEMA_INVALID, 0,
                    "SCHEMA_REPAIR_REQUESTED", schemaFailure.errors());
            String repairPrompt = structuredRepairPrompt(schemaFailure.errors());
            ModelResponse repaired = requestStructuredPlan(List.of(
                            AgentMessage.userGoal(originalPrompt),
                            AgentMessage.userGoal(repairPrompt)),
                    executionGuard, planOperationIdentity(revisionNumber,planningAttemptOrdinal,
                            "repair"),
                    "PLAN_STRUCTURED_REPAIR");
            String repairedContent = requireFinalPlan(repaired);
            try {
                JsonNode repairedRoot = parser.parse(repairedContent, maxPlanBytes);
                observeCompatibility(parser, repairedContent,
                        PlanCompatibilityObserver.ParseStatus.ACCEPTED, 1,
                        "ACCEPTED_AFTER_SCHEMA_REPAIR", List.of());
                return new ParsedStructuredPlan(repairedRoot, true);
            } catch (StructuredPlanResponseParser.ParseFailure repairFailure) {
                observeCompatibility(parser, repairedContent, status(repairFailure), 1,
                        "STRUCTURED_PLAN_REPAIR_FAILED", errors(repairFailure));
                throw new PlanGenerationException("STRUCTURED_PLAN_REPAIR_FAILED",
                        "STRUCTURED_PLAN_REPAIR_FAILED: "
                                + repairFailure.getMessage());
            }
        } catch (StructuredPlanResponseParser.ParseFailure parseFailure) {
            observeCompatibility(parser, content, status(parseFailure), 0,
                    "STRUCTURED_PLAN_PARSE_FAILED", errors(parseFailure));
            throw new PlanGenerationException(parseFailure.getMessage());
        }
    }

    private void observeCompatibility(StructuredPlanResponseParser parser, String content,
            PlanCompatibilityObserver.ParseStatus status, int repairAttemptCount,
            String terminalReason, List<String> validationErrors) {
        StructuredPlanResponseParser.SchemaSummary summary = parser.schemaSummary(content);
        compatibilityObserver.observed(new PlanCompatibilityObserver.Fact(status,
                parser.wireSchemaVersion(),
                summary.unknownFields().size(), summary.missingRequiredFields().size(),
                parser.normalizationCount(content), repairAttemptCount, terminalReason,
                summary.requiredFields(), summary.missingRequiredFields(),
                summary.unknownFields(), validationErrors));
    }

    private static PlanCompatibilityObserver.ParseStatus status(
            StructuredPlanResponseParser.ParseFailure failure) {
        if (failure instanceof StructuredPlanResponseParser.SchemaFailure) {
            return PlanCompatibilityObserver.ParseStatus.SCHEMA_INVALID;
        }
        if (failure instanceof StructuredPlanResponseParser.ContentBoundsFailure) {
            return PlanCompatibilityObserver.ParseStatus.CONTENT_BOUNDS_INVALID;
        }
        return PlanCompatibilityObserver.ParseStatus.PARSE_INVALID;
    }

    private static List<String> errors(StructuredPlanResponseParser.ParseFailure failure) {
        if (failure instanceof StructuredPlanResponseParser.SchemaFailure schema) {
            return schema.errors();
        }
        if (failure instanceof StructuredPlanResponseParser.ContentBoundsFailure bounds) {
            return bounds.errors();
        }
        return List.of(failure.getMessage().split(" ", 2)[0]);
    }

    private record ParsedStructuredPlan(JsonNode root, boolean repairRequestUsed) { }

    private String structuredRepairPrompt(List<String> validationErrors) {
        String errors = String.join(", ", validationErrors.stream()
                .limit(8).toList());
        return """
                Repair the Structured Plan schema. Return only one JSON object.
                Required root fields: acceptanceCriteria, assumptions, risks,
                verificationStrategy, steps.
                Each step requires title, definition, dependsOn, completionRule,
                requiredEvidenceKinds, effectClass and toolRequirements.
                Every TOOL_CALL step requires one or more typed toolRequirements; all other
                steps require an empty toolRequirements array. Do not emit selector digests.
                Use minSuccesses=1, maxOccurrences=1 and
                freshnessPolicy=RESUME_REUSE_ONLY by default. Set maxOccurrences greater than 1
                or freshnessPolicy=FRESH_EXECUTION_REQUIRED only when the Plan step explicitly
                requires polling or repeated fresh execution.
                Allowed completionRule values: ALL_REQUIRED_EVIDENCE,
                ANY_REQUIRED_EVIDENCE, SNAPSHOT_CREATED, VERIFICATION_PASSED.
                Allowed requiredEvidenceKinds values: TOOL_CALL, SNAPSHOT,
                VERIFICATION_RESULT.
                Allowed effectClass values: READ, WRITE, VERIFY, ARTIFACT, GENERAL.
                Validation errors: %s
                Do not include Markdown, commentary, tool calls, or completed-work claims.
                """.formatted(errors);
    }

    private static String providerFailureMessage(
            ModelResponse.ProviderErrorKind kind) {
        if (kind == null) {
            return "PROVIDER_REQUEST_FAILURE: planning provider failed";
        }
        return switch (kind) {
            case PROVIDER_CONNECTION_FAILURE ->
                    "PROVIDER_CONNECTION_FAILURE: connection refused or provider unreachable";
            case PROVIDER_TIMEOUT ->
                    "PROVIDER_TIMEOUT: planning request timed out";
            case PROVIDER_MODEL_UNAVAILABLE ->
                    "PROVIDER_MODEL_UNAVAILABLE: configured planning model is unavailable";
            case PROVIDER_AUTHENTICATION_FAILURE ->
                    "PROVIDER_AUTHENTICATION_FAILURE: API credentials were rejected";
            case PROVIDER_RATE_LIMITED ->
                    "PROVIDER_RATE_LIMITED: planning provider rate limit was reached";
            case PROVIDER_SERVER_FAILURE ->
                    "PROVIDER_SERVER_FAILURE: planning provider returned a server error";
            case PROVIDER_HTTP_FAILURE ->
                    "PROVIDER_HTTP_FAILURE: planning provider returned an HTTP error";
            case PROVIDER_RESPONSE_PARSE_FAILURE ->
                    "PROVIDER_MALFORMED_RESPONSE: planning provider returned malformed JSON";
            case PROVIDER_EMPTY_RESPONSE ->
                    "PROVIDER_EMPTY_RESPONSE: planning provider returned no usable content";
            case PROVIDER_RESPONSE_INCOMPLETE -> "PROVIDER_RESPONSE_INCOMPLETE: response did not complete";
            case PROVIDER_FINAL_ANSWER_CONTRACT_FAILURE ->
                    "PROVIDER_FINAL_ANSWER_CONTRACT_FAILURE: invalid managed user answer";
            case PROVIDER_REQUEST_SERIALIZATION_FAILURE ->
                    "PROVIDER_REQUEST_SERIALIZATION_FAILURE: planning request could not be serialized";
            case PROVIDER_PRIVATE_CONTINUATION_PROTOCOL_FAILURE ->
                    "PROVIDER_PRIVATE_CONTINUATION_PROTOCOL_FAILURE: Provider continuation protocol was incomplete";
            case PROVIDER_PRIVATE_CONTINUATION_HTTP_400 ->
                    "PROVIDER_PRIVATE_CONTINUATION_HTTP_400: Provider rejected the private Tool continuation";
            case PROVIDER_PRIVATE_CONTINUATION_INCOMPLETE ->
                    "PROVIDER_PRIVATE_CONTINUATION_INCOMPLETE: Provider Tool continuation state was incomplete";
            case PROVIDER_PRIVATE_CONTINUATION_INCOMPLETE_AFTER_RETRY ->
                    "PROVIDER_PRIVATE_CONTINUATION_INCOMPLETE_AFTER_RETRY: Provider Tool continuation remained incomplete after one bounded retry";
            case TOOL_CALL_ID_MISSING ->
                    "TOOL_CALL_ID_MISSING: Provider Tool Call did not include an identity";
            case TOOL_CALL_ID_DUPLICATE ->
                    "TOOL_CALL_ID_DUPLICATE: Provider Tool Call identity was reused";
            case FINISH_REASON_CONFLICT ->
                    "FINISH_REASON_CONFLICT: Provider finish reason did not match its Tool Call response";
            case TOOL_RESULT_MISSING_FOR_BATCH_CONTINUATION ->
                    "TOOL_RESULT_MISSING_FOR_BATCH_CONTINUATION: Tool batch continuation was incomplete";
            case TOOL_RESULT_BATCH_CONTINUATION_MISMATCH ->
                    "TOOL_RESULT_BATCH_CONTINUATION_MISMATCH: Tool batch result identities did not exactly match the assistant turn";
            case PROVIDER_PRIVATE_CONTINUATION_UNAVAILABLE ->
                    "PROVIDER_PRIVATE_CONTINUATION_UNAVAILABLE: interrupted Provider Tool chains cannot be resumed safely";
            case PROVIDER_CONFIGURATION_FAILURE ->
                    "PROVIDER_CONFIGURATION_FAILURE: planning provider configuration is invalid";
            case PROVIDER_REQUEST_FAILURE ->
                    "PROVIDER_REQUEST_FAILURE: planning request failed";
            case PROVIDER_TRANSPORT_INTERRUPTED ->
                    "PROVIDER_TRANSPORT_INTERRUPTED: planning provider connection was interrupted";
            case PROVIDER_TRANSPORT_RETRY_EXHAUSTED ->
                    "PROVIDER_TRANSPORT_RETRY_EXHAUSTED: planning provider failed after the bounded retry budget";
            case PROVIDER_RETRY_ADMISSION_REJECTED ->
                    "PROVIDER_RETRY_ADMISSION_REJECTED: planning retry was rejected because durable execution state changed";
            case PROVIDER_LEGACY_ERROR ->
                    "PROVIDER_LEGACY_ERROR: planning provider returned a legacy error";
        };
    }

    private static RuntimeException planningInvocationFailure(
            RuntimeException error) {
        if (error instanceof CancellationToken.TaskCancellationException
                || Thread.currentThread().isInterrupted()) {
            return error instanceof CancellationToken.TaskCancellationException
                    ? error : new CancellationToken.TaskCancellationException(false);
        }
        if (error instanceof ContextualModelProvider.ContextGenerationException) {
            String code = error.getMessage();
            String safeCode = code != null && code.matches("[A-Z0-9_]{1,64}")
                    ? code : "CONTEXT_PREPARATION_FAILED";
            return new PlanGenerationException(
                    "PLAN_CONTEXT_PREPARATION_FAILURE: " + safeCode, error);
        }
        if(error instanceof TaskProviderBudgetGate.AdmissionException admission){
            return new PlanGenerationException(admission.code(),admission);
        }
        return new PlanGenerationException(
                "PROVIDER_REQUEST_EXCEPTION: planning request failed before a typed response",
                error);
    }

    private void admitTaskProviderBudget(String taskId,String runId){
        TaskProviderBudgetGate.Admission admission;
        try{
            admission=Objects.requireNonNull(
                    taskProviderBudgetGate.evaluate(taskId,runId),"admission");
        }catch(TaskProviderBudgetGate.AdmissionException rejected){
            throw rejected;
        }catch(RuntimeException unavailable){
            throw new TaskProviderBudgetGate.AdmissionException(
                    "TASK_BUDGET_EVIDENCE_INCOMPLETE",unavailable);
        }
        if(!admission.admitted())
            throw new TaskProviderBudgetGate.AdmissionException(admission.code());
    }

    private String planningPrompt(String goal, String skillContext) {
        String prompt = "Create a concise, actionable implementation plan for the coding task below. "
                + "Do not call tools and do not claim that work has already been completed. "
                + "Include verification steps and important safety constraints.\n\nTask:\n" + goal;
        if (skillContext == null || skillContext.isBlank()) {
            return prompt;
        }
        return prompt + "\n\n" + sanitizer.sanitizeAndTruncate(
                skillContext, PersistenceSanitizer.DEFAULT_MAX_TEXT_BYTES);
    }

    private String structuredPlanningPrompt(String goal, String skillContext,
            PlanCompletionProfilePolicy.Capabilities capabilities) {
        if (runtimeMode == PlanRuntimeMode.DEFAULT_ADVISORY) {
            String prompt = "Return only one JSON object with fields acceptanceCriteria, assumptions, "
                    + "risks, verificationStrategy and steps. steps must be a non-empty array of "
                    + "objects containing only title, definition and dependsOn (zero-based step "
                    + "indexes). This is an Advisory Plan for intent, decomposition, progress and "
                    + "audit only. Do not emit Tool authority selectors, cardinality, freshness, "
                    + "permissions or approval claims. The Plan cannot grant Tool access. "
                    + "Use between 1 and 16 concise, independently useful steps. Do not call tools "
                    + "or claim work is complete.\n\nTask:\n" + goal;
            return skillContext == null || skillContext.isBlank() ? prompt : prompt + "\n\n"
                    + sanitizer.sanitizeAndTruncate(skillContext,
                            PersistenceSanitizer.DEFAULT_MAX_TEXT_BYTES);
        }
        String prompt = "Return only one JSON object with fields acceptanceCriteria, assumptions, risks, "
                + "verificationStrategy and steps. steps must be a non-empty array of objects with title, "
                + "definition, dependsOn (zero-based step indexes), completionRule, requiredEvidenceKinds "
                + "effectClass and toolRequirements. completionRule must be ALL_REQUIRED_EVIDENCE, ANY_REQUIRED_EVIDENCE, "
                + "SNAPSHOT_CREATED or VERIFICATION_PASSED. requiredEvidenceKinds must be a non-empty array "
                + "containing only TOOL_CALL, SNAPSHOT or VERIFICATION_RESULT and must match specialized rules. "
                + "effectClass must be READ, WRITE, VERIFY, ARTIFACT or GENERAL. Choose requirements that the "
                + "declared step can actually produce. READ and GENERAL steps should normally use TOOL_CALL "
                + "evidence. SNAPSHOT evidence is only valid for a WRITE step that applies a file mutation. "
                + "Every TOOL_CALL step must directly emit a non-empty toolRequirements array; every other step "
                + "must emit an empty array. Each requirement has requirementId, requirementOrder (contiguous "
                + "from zero), toolName, effectClass, authoritySelector, minSuccesses, maxOccurrences and "
                + "freshnessPolicy. Use minSuccesses=1, maxOccurrences=1 and "
                + "freshnessPolicy=RESUME_REUSE_ONLY by default. Set maxOccurrences greater than 1 or "
                + "freshnessPolicy=FRESH_EXECUTION_REQUIRED only when the Plan step explicitly requires "
                + "polling or repeated fresh execution. Supported exact wire selectors are: "
                + "read_file {kind:EXACT_PATH,path,maxLines?,includeInternal?}; list_files "
                + "{kind:EXACT_PATH,path,includeInternal?}; write_file {kind:EXACT_PATH,path,overwrite?}; "
                + "grep {kind:EXACT_GREP,root?,pattern,includeInternal?}; run_command "
                + "{kind:EXACT_COMMAND,command}; git_diff {kind:EXACT_GIT_DIFF,subcommand?,args?}. "
                + "Only toolName values read_file, list_files, write_file, grep, run_command and git_diff are "
                + "supported. Use normalized exact workspace-relative paths without glob or subtree authority. "
                + "Do not emit canonicalJson, selector digest, write_file content, natural-language Tool authority, "
                + "or any unknown selector field. "
                + verificationConstraint(capabilities)
                + " Use between 1 and 16 steps. Keep each step title short and action-focused. "
                + "Use each definition only for that step's scope, action, and acceptance condition. "
                + "Put global constraints in acceptanceCriteria, assumptions, risks, or "
                + "verificationStrategy; do not copy the complete user goal, global prohibitions, "
                + "or final-answer format into every step. Every step must remain independently "
                + "executable without repeating global rules. Do not add any tool, file, target, or "
                + "authority not authorized by the user. Do not add a synthesis-only step whose "
                + "evidence cannot be produced. Do not call tools or mark work complete.\n\nTask:\n" + goal;
        return skillContext == null || skillContext.isBlank() ? prompt : prompt + "\n\n"
                + sanitizer.sanitizeAndTruncate(skillContext, PersistenceSanitizer.DEFAULT_MAX_TEXT_BYTES);
    }

    private String completionProfileRepairPrompt(List<String> errors,
            PlanCompletionProfilePolicy.Capabilities capabilities) {
        return """
                Repair only the Structured Plan completion profiles and any step boundaries needed
                to make every step independently executable. Return one complete JSON object that
                satisfies the original schema. Do not include Markdown or commentary.
                Runtime completion-profile errors: %s
                Runtime capability: %s
                READ and GENERAL steps must use evidence they can produce through tool execution.
                SNAPSHOT is only valid for a WRITE step that applies a file mutation.
                Do not create a synthesis-only step and do not claim work is complete.
                """.formatted(String.join(", ", errors.stream().limit(8).toList()),
                verificationConstraint(capabilities).strip());
    }

    private static String verificationConstraint(
            PlanCompletionProfilePolicy.Capabilities capabilities) {
        if (capabilities == null || capabilities.verificationAvailable()) {
            return "Verification evidence is available only for a real configured verification step.";
        }
        return "No verification command is configured: do not use VERIFICATION_PASSED or require "
                + "VERIFICATION_RESULT.";
    }
    private static String requiredText(JsonNode node, String name) {
        String value = text(node, name);
        if (value.isBlank()) throw new PlanGenerationException("Structured Plan field is required: " + name);
        return value;
    }
    private static String text(JsonNode node, String name) {
        JsonNode value = node.path(name);
        try { return value.isMissingNode() || value.isNull() ? "" : value.isTextual()
                ? value.asText() : JSON.writeValueAsString(value); }
        catch (Exception error) { throw new PlanGenerationException("Invalid structured Plan field", error); }
    }
    private static <E extends Enum<E>> E enumValue(JsonNode node, String name, Class<E> type) {
        String value = requiredText(node, name);
        try { return Enum.valueOf(type, value); }
        catch (IllegalArgumentException error) {
            throw new PlanGenerationException("Unsupported Structured Plan " + name + ": " + value);
        }
    }
    private static <E extends Enum<E>> List<E> enumArray(JsonNode node, String name, Class<E> type) {
        JsonNode values = node.path(name);
        if (!values.isArray() || values.isEmpty()) {
            throw new PlanGenerationException("Structured Plan field is required: " + name);
        }
        List<E> result = new ArrayList<>();
        for (JsonNode value : values) {
            if (!value.isTextual()) throw new PlanGenerationException("Invalid " + name);
            try { result.add(Enum.valueOf(type, value.asText())); }
            catch (IllegalArgumentException error) {
                throw new PlanGenerationException("Unsupported Structured Plan " + name + ": " + value.asText());
            }
        }
        return List.copyOf(result);
    }
    private static List<ToolRequirement> toolRequirements(JsonNode step) {
        JsonNode values = step.path("toolRequirements");
        if (!values.isArray()) throw new PlanGenerationException(
                "Structured Plan field is required: toolRequirements");
        List<ToolRequirement> result = new ArrayList<>();
        for (JsonNode value : values) {
            String requirementId = requiredText(value, "requirementId");
            JsonNode order = value.path("requirementOrder");
            JsonNode minimum = value.path("minSuccesses");
            JsonNode maximum = value.path("maxOccurrences");
            if (!order.isIntegralNumber() || !minimum.isIntegralNumber()
                    || !maximum.isIntegralNumber()) {
                throw new PlanGenerationException("Tool requirement cardinality/order must be integer");
            }
            String toolName = requiredText(value, "toolName");
            EffectClass effect = enumValue(value, "effectClass", EffectClass.class);
            FreshnessPolicy freshness = enumValue(value, "freshnessPolicy", FreshnessPolicy.class);
            try {
                result.add(new ToolRequirement(requirementId, order.asInt(), toolName, effect,
                        PlanToolRequirementPolicy.normalize(toolName, value.path("authoritySelector")),
                        minimum.asInt(), maximum.asInt(), freshness));
            } catch (IllegalArgumentException error) {
                throw new PlanGenerationException("Invalid structured Tool requirement", error);
            }
        }
        return List.copyOf(result);
    }
    private static String sha(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception error) { throw new IllegalStateException("SHA-256 unavailable", error); }
    }

    private void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }

    public static class PlanGenerationException extends RuntimeException {
        private final String code;
        public PlanGenerationException(String message) {
            this("PLAN_GENERATION_FAILURE",message,null);
        }

        public PlanGenerationException(String message, Throwable cause) {
            this("PLAN_GENERATION_FAILURE",message,cause);
        }

        public PlanGenerationException(String code,String message) {
            this(code,message,null);
        }

        private PlanGenerationException(String code,String message,Throwable cause) {
            super(message,cause);
            if(code==null||code.isBlank())throw new IllegalArgumentException(
                    "Typed Plan failure code is required");
            this.code=code;
        }

        public String code(){return code;}
    }

    static String planOperationIdentity(int revisionNumber,int planningAttemptOrdinal,
            String suffix){
        String base="plan:"+revisionNumber;
        if(planningAttemptOrdinal>1)base+=":attempt:"+planningAttemptOrdinal;
        return suffix==null?base:base+":"+suffix;
    }
}
