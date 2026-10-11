package com.paicli.state;

import com.paicli.identity.CanonicalId;
import com.paicli.state.PlanRequirementStore.EvidenceSlotBinding;
import com.paicli.state.PlanRequirementStore.OccurrenceSlotBinding;
import com.paicli.state.PlanRequirementStore.RequirementRecord;
import com.paicli.state.StructuredPlanModels.FreshnessPolicy;
import com.paicli.state.StructuredPlanModels.ToolRequirement;
import com.paicli.state.PlanCompletionModels.Evidence;
import com.paicli.state.PlanCompletionModels.EvidenceKind;
import com.paicli.state.PlanCompletionModels.ProducerKind;
import com.paicli.state.PlanCompletionModels.CompletionRuleType;
import com.paicli.state.PlanExecutionModels.StepState;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** One semantic x/y evaluator shared by completion, recovery, and interactive projection. */
public final class PlanEvidenceSemanticEvaluator {
    public record RequirementProgress(RequirementRecord requirement,int requiredSuccesses,
            int satisfiedSuccesses,int boundOccurrences,int matchedRunning,
            int remainingSuccesses,int remainingOccurrences){
        public RequirementProgress{
            Objects.requireNonNull(requirement);
            if(requiredSuccesses<1||satisfiedSuccesses<0||boundOccurrences<0
                    ||matchedRunning<0||remainingSuccesses<0||remainingOccurrences<0)
                throw new IllegalArgumentException("Invalid requirement progress");
        }
        public boolean satisfied(){return remainingSuccesses==0;}
    }

    public record StepProgress(CanonicalId stepRevisionId,int requiredSuccesses,
            int satisfiedSuccesses,int boundOccurrences,int matchedRunning,
            List<RequirementProgress> requirements,List<EvidenceKindProgress> typedEvidence){
        public StepProgress{
            requirements=List.copyOf(requirements);typedEvidence=List.copyOf(typedEvidence);
        }
        public boolean satisfied(){return satisfiedSuccesses==requiredSuccesses;}
        public List<RequirementProgress> unmet(){
            return requirements.stream().filter(value->!value.satisfied()).toList();
        }
    }

    public record EvidenceKindProgress(EvidenceKind evidenceKind,int requiredSuccesses,
            int satisfiedSuccesses){
        public EvidenceKindProgress{
            Objects.requireNonNull(evidenceKind);
            if(requiredSuccesses!=1||satisfiedSuccesses<0||satisfiedSuccesses>1)
                throw new IllegalArgumentException("Invalid typed Evidence progress");
        }
    }

    public record Evaluation(CanonicalId taskId,CanonicalId planRevisionId,
            CanonicalId evaluationRunId,int requiredSuccesses,int satisfiedSuccesses,
            int boundOccurrences,int matchedRunning,boolean integrityValid,
            List<StepProgress> steps){
        public Evaluation{steps=List.copyOf(steps);}
        public boolean satisfied(){return integrityValid&&satisfiedSuccesses==requiredSuccesses;}
        public StepProgress step(CanonicalId stepId){
            return steps.stream().filter(value->value.stepRevisionId().equals(stepId))
                    .findFirst().orElse(null);
        }
    }

    private final Connection connection;
    public PlanEvidenceSemanticEvaluator(Connection connection){
        this.connection=Objects.requireNonNull(connection);
    }

    public Evaluation evaluate(CanonicalId taskId,CanonicalId revisionId,
            CanonicalId evaluationRunId){
        PlanAuthorityStore.RevisionRead revision=
                new PlanAuthorityStore(connection).readRevisionDefinition(taskId,revisionId);
        if(revision.status()!=PlanAuthorityStore.Status.SUCCESS
                ||revision.revision().canonicalSchemaVersion()
                    !=StructuredPlanModels.Revision.CANONICAL_SCHEMA_V3)
            return new Evaluation(taskId,revisionId,evaluationRunId,0,0,0,0,false,List.of());
        PlanRequirementStore store=new PlanRequirementStore(connection);
        PlanExecutionStore executions=new PlanExecutionStore(connection);
        List<PlanExecutionModels.StepExecution> stepStates=
                executions.readStates(taskId,revisionId);
        PlanCompletionStore completions=new PlanCompletionStore(connection);
        List<Evidence> allEvidence=completions.readEvidence(taskId,revisionId);
        List<StepProgress> steps=new ArrayList<>();
        boolean integrity=true;
        for(var step:revision.revision().steps()){
            StepState stepState=stepStates.stream().filter(value->value.stepRevisionId()
                    .equals(step.stepRevisionId())).map(PlanExecutionModels.StepExecution::state)
                    .findFirst().orElse(null);
            if(stepState==null)integrity=false;
            boolean countableState=stepState==StepState.RUNNING||stepState==StepState.SUCCEEDED;
            boolean futureState=stepState==StepState.PENDING||stepState==StepState.READY;
            List<RequirementRecord> requirements=store.requirements(taskId,revisionId,
                    step.stepRevisionId());
            boolean toolEvidenceDeclared=step.requiredEvidenceKinds()
                    .contains(EvidenceKind.TOOL_CALL);
            if(toolEvidenceDeclared!=!requirements.isEmpty())integrity=false;
            List<OccurrenceSlotBinding> occurrences=store.bindings(taskId,revisionId,
                    step.stepRevisionId());
            List<EvidenceSlotBinding> slotEvidence=store.evidenceBindings(taskId,revisionId,
                    step.stepRevisionId());
            if(futureState&&(!occurrences.isEmpty()||!slotEvidence.isEmpty()))integrity=false;
            List<RequirementProgress> progress=new ArrayList<>();
            for(RequirementRecord record:requirements){
                ToolRequirement requirement=record.requirement();
                List<OccurrenceSlotBinding> ownedOccurrences=occurrences.stream().filter(value->
                        value.requirementId().equals(requirement.requirementId())).toList();
                List<EvidenceSlotBinding> ownedEvidence=slotEvidence.stream().filter(value->
                        value.requirementId().equals(requirement.requirementId())).toList();
                Set<Integer> occurrenceSlots=new HashSet<>();
                Set<Integer> evidenceSlots=new HashSet<>();
                for(OccurrenceSlotBinding value:ownedOccurrences){
                    integrity&=value.selectorDigest().equals(
                            requirement.authoritySelector().digest())
                            &&value.slotOrdinal()<=requirement.maxOccurrences()
                            &&occurrenceSlots.add(value.slotOrdinal());
                }
                int successful=0;
                for(EvidenceSlotBinding value:ownedEvidence){
                    boolean fresh=requirement.freshnessPolicy()
                            ==FreshnessPolicy.RESUME_REUSE_ONLY
                            ||evaluationRunId!=null&&evaluationRunId.equals(value.runId());
                    boolean matching=occurrenceSlots.contains(value.slotOrdinal())
                            &&value.selectorDigest().equals(
                                    requirement.authoritySelector().digest())
                            &&evidenceSlots.add(value.slotOrdinal());
                    integrity&=matching;
                    if(matching&&fresh)successful++;
                }
                int satisfied=countableState
                        ?Math.min(requirement.minSuccesses(),successful):0;
                int running=(int)ownedOccurrences.stream().filter(value->
                        "STARTED".equals(value.occurrenceStatus())
                        &&(evaluationRunId==null||evaluationRunId.equals(value.runId()))).count();
                progress.add(new RequirementProgress(record,requirement.minSuccesses(),satisfied,
                        ownedOccurrences.size(),running,requirement.minSuccesses()-satisfied,
                        Math.max(0,requirement.maxOccurrences()-ownedOccurrences.size())));
            }
            List<EvidenceKindProgress> typed=new ArrayList<>();
            for(EvidenceKind kind:step.requiredEvidenceKinds()){
                if(kind==EvidenceKind.TOOL_CALL)continue;
                ProducerKind expected=kind==EvidenceKind.SNAPSHOT?ProducerKind.SNAPSHOT
                        :kind==EvidenceKind.VERIFICATION_RESULT?ProducerKind.VERIFICATION:null;
                if(expected==null){integrity=false;continue;}
                List<Evidence> candidates=allEvidence.stream().filter(value->
                        value.stepRevisionId().equals(step.stepRevisionId())
                        &&value.kind()==kind).toList();
                if(futureState&&!candidates.isEmpty())integrity=false;
                boolean valid=false;
                for(Evidence evidence:candidates){
                    boolean candidateValid=evidence.producerKind()==expected
                            &&"SUCCEEDED".equals(evidence.observedStatus())
                            &&completions.semanticProducerIntegrityValid(evidence);
                    integrity&=candidateValid;
                    valid|=candidateValid;
                }
                typed.add(new EvidenceKindProgress(kind,1,
                        countableState&&valid?1:0));
            }
            int toolRequired=progress.stream()
                    .mapToInt(RequirementProgress::requiredSuccesses).sum();
            int toolSatisfied=progress.stream()
                    .mapToInt(RequirementProgress::satisfiedSuccesses).sum();
            int typedRequired=typed.stream()
                    .mapToInt(EvidenceKindProgress::requiredSuccesses).sum();
            int typedSatisfied=typed.stream()
                    .mapToInt(EvidenceKindProgress::satisfiedSuccesses).sum();
            int required;
            int satisfied;
            CompletionRuleType rule=step.completionRule();
            switch(rule){
                case ALL_REQUIRED_EVIDENCE -> {
                    required=toolRequired+typedRequired;
                    satisfied=toolSatisfied+typedSatisfied;
                }
                case ANY_REQUIRED_EVIDENCE -> {
                    boolean toolAlternative=toolEvidenceDeclared&&!progress.isEmpty()
                            &&toolSatisfied==toolRequired;
                    boolean typedAlternative=typed.stream().anyMatch(value->
                            value.satisfiedSuccesses()==value.requiredSuccesses());
                    required=1;
                    satisfied=toolAlternative||typedAlternative?1:0;
                }
                case SNAPSHOT_CREATED -> {
                    EvidenceKindProgress snapshot=typed.stream().filter(value->
                            value.evidenceKind()==EvidenceKind.SNAPSHOT).findFirst().orElse(null);
                    required=1;satisfied=snapshot==null?0:snapshot.satisfiedSuccesses();
                }
                case VERIFICATION_PASSED -> {
                    EvidenceKindProgress verification=typed.stream().filter(value->
                            value.evidenceKind()==EvidenceKind.VERIFICATION_RESULT)
                            .findFirst().orElse(null);
                    required=1;satisfied=verification==null?0:verification.satisfiedSuccesses();
                }
                default -> {
                    integrity=false;required=1;satisfied=0;
                }
            }
            int bound=progress.stream().mapToInt(RequirementProgress::boundOccurrences).sum();
            int running=progress.stream().mapToInt(RequirementProgress::matchedRunning).sum();
            steps.add(new StepProgress(step.stepRevisionId(),required,satisfied,bound,running,
                    progress,typed));
        }
        int required=steps.stream().mapToInt(StepProgress::requiredSuccesses).sum();
        int satisfied=steps.stream().mapToInt(StepProgress::satisfiedSuccesses).sum();
        int bound=steps.stream().mapToInt(StepProgress::boundOccurrences).sum();
        int running=steps.stream().mapToInt(StepProgress::matchedRunning).sum();
        return new Evaluation(taskId,revisionId,evaluationRunId,required,satisfied,bound,running,
                integrity,steps);
    }
}
