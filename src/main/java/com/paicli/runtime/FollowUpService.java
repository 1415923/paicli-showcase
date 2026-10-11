package com.paicli.runtime;

import com.paicli.config.Config;
import com.paicli.identity.CanonicalId;
import com.paicli.state.*;
import com.paicli.state.FollowUpModels.*;
import com.paicli.state.StructuredPlanModels.PlanApplicability;

import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

/** Orchestrates provider-free Follow-up admission. Child execution starts only after admission. */
public final class FollowUpService {
    private final StateStore state;private final InstructionManifestRuntime manifests;
    private final Config config;private final RuntimeManifest runtimeManifest;private final int maxInstructionChars;
    private final FollowUpCanonicalizer canonical=new FollowUpCanonicalizer();
    private final FollowUpClassifier classifier=new FollowUpClassifier();

    public FollowUpService(StateStore state,InstructionManifestRuntime manifests,Config config,
            RuntimeManifest runtimeManifest,int maxInstructionChars){
        this.state=Objects.requireNonNull(state);this.manifests=Objects.requireNonNull(manifests);
        this.config=Objects.requireNonNull(config);this.runtimeManifest=Objects.requireNonNull(runtimeManifest);
        this.maxInstructionChars=maxInstructionChars;
    }

    public Outcome begin(CanonicalId threadId,String raw,boolean explicit,String owner,String session,Instant at)throws SQLException{
        return begin(threadId,raw,explicit,owner,session,at,null,-1);
    }

    /** The optional Parent token freezes terminal-input ownership at blocking-read start. */
    public Outcome begin(CanonicalId threadId,String raw,boolean explicit,String owner,String session,Instant at,
            CanonicalId expectedParent,long expectedHeadVersion)throws SQLException{
        if(expectedParent!=null){String expected=expectedParent.value()+"|"+expectedHeadVersion;
            if(!state.followUps.currentHeadToken(threadId).filter(expected::equals).isPresent())
                throw new FollowUpException("STALE_HEAD");}
        ParentAnchor parent=state.followUps.currentEligibleParent(threadId)
                .orElseThrow(()->new FollowUpException("FOLLOW_UP_NOT_ELIGIBLE"));
        if(expectedParent!=null&&(!expectedParent.equals(parent.taskId())||expectedHeadVersion!=parent.headVersion()))
            throw new FollowUpException("STALE_HEAD");
        String instruction=canonical.sanitizeInstruction(raw);
        IntentClassification intent=explicit?new IntentClassification(Intent.CONTINUE_CURRENT_THREAD,
                FollowUpClassifier.VERSION,"EXPLICIT_CONTINUE"):classifier.classify(instruction);
        Candidate candidate=state.followUps.createCandidate(parent,instruction,canonical.sha256(instruction),
                explicit?"EXPLICIT_CONTINUE":"NATURAL_TERMINAL_INPUT",owner,session,intent,at);
        if(candidate.state()==CandidateState.PREPARING)return prepareAndMaybeAdmit(candidate,parent,at);
        return new Outcome(candidate,null,intent.intent()==Intent.CREATE_NEW_THREAD?"CREATE_NEW_THREAD":candidate.state().name());
    }

    public Outcome confirm(CanonicalId id,ConfirmationKind kind,long version,String owner,String session,
            ConfirmationDecision decision,Instant at)throws SQLException{
        Candidate candidate=state.followUps.confirm(id,kind,version,owner,session,decision,at);
        if(candidate.state()==CandidateState.PREPARING){
            ParentAnchor parent=state.followUps.currentEligibleParent(candidate.threadId())
                    .orElseThrow(()->new FollowUpException("FOLLOW_UP_NOT_ELIGIBLE"));
            return prepareAndMaybeAdmit(candidate,parent,at);
        }
        if(candidate.state()==CandidateState.READY_TO_ADMIT)return admit(candidate,at);
        if(candidate.state()==CandidateState.ADMITTED)return new Outcome(candidate,
                state.followUps.findAdmittedChild(id).orElseThrow(),"ADMITTED_EXISTING");
        return new Outcome(candidate,null,candidate.state().name());
    }

    public Outcome resume(CanonicalId threadId,Instant at)throws SQLException{
        Candidate candidate=state.followUps.findPending(threadId).orElseThrow(()->new FollowUpException("NO_PENDING_FOLLOW_UP"));
        if(candidate.state()==CandidateState.PREPARING){
            ParentAnchor parent=state.followUps.currentEligibleParent(threadId)
                    .orElseThrow(()->new FollowUpException("FOLLOW_UP_NOT_ELIGIBLE"));
            return prepareAndMaybeAdmit(candidate,parent,at);
        }
        if(candidate.state()==CandidateState.READY_TO_ADMIT)return admit(candidate,at);
        return new Outcome(candidate,null,candidate.state().name());
    }

    public Optional<Outcome> recoverAdmittedChildStart(CanonicalId threadId,Instant at)throws SQLException{
        Optional<PendingChildStart> pending=state.followUps.findRecoverableAdmittedChild(threadId,at);
        return pending.map(value->new Outcome(value.candidate(),value.child(),"ADMITTED_EXISTING"));
    }

    private Outcome prepareAndMaybeAdmit(Candidate candidate,ParentAnchor parent,Instant at)throws SQLException{
        List<CapsuleFact> facts=new ArrayList<>();
        facts.add(new CapsuleFact(FactType.PARENT_GOAL,parent.taskId().value(),parent.goal(),null,null,"DURABLE_TASK_GOAL"));
        String completionSummary=parent.status()+"|evaluation="+parent.completionEvaluationNumber()
                +"|evidence="+parent.completionEvidenceHashes().size();
        facts.add(new CapsuleFact(FactType.COMPLETION,parent.taskId().value(),completionSummary,parent.completionAnchorId(),
                parent.completionAnchorType().equals("COMPLETION_DECISION")?parent.completionRulesHash():null,
                "R1C_VALIDATED_COMPLETION_AUTHORITY"));
        facts.add(new CapsuleFact(FactType.MANIFEST,parent.taskId().value(),"PARENT_INSTRUCTION_MANIFEST",
                parent.manifestId(),parent.manifestHash(),"COMMITTED_PARENT_MANIFEST"));
        if(parent.planRevisionId()!=null)facts.add(new CapsuleFact(FactType.PLAN,parent.taskId().value(),"PARENT_ACTIVE_FINAL_PLAN",
                parent.planRevisionId().value(),parent.planHash(),"PARENT_PLAN_REFERENCE_ONLY"));
        facts.addAll(state.followUps.parentReferenceFacts(parent.taskId(),parent.completionEvidenceHashes()));
        List<CapsuleFact> canonicalFacts=canonical.canonicalFacts(facts);
        CapsuleDefinition capsule=new CapsuleDefinition(candidate.capsuleId(),FollowUpCanonicalizer.SCHEMA_VERSION,
                parent.threadId(),parent.taskId(),parent.terminalRunId(),parent.generationId(),parent.completionAnchorType(),
                parent.completionAnchorId(),parent.manifestId(),parent.manifestHash(),parent.planRevisionId(),parent.planHash(),
                canonical.hash(parent,canonicalFacts),at,canonicalFacts);
        InstructionManifestRuntime.PreparedChildManifest fresh=manifests.prepareChild(maxInstructionChars,at);
        ManifestDiffRiskClassifier riskClassifier=new ManifestDiffRiskClassifier();
        List<ManifestDiff> diffs=riskClassifier.diff(state.followUps.parentManifestEntries(parent.taskId()),fresh.candidate().entries());
        ManifestDiffRiskClassifier.Assessment assessment=riskClassifier.classify(diffs);
        PreparedManifest prepared=new PreparedManifest("manifest-"+candidate.capsuleId().value(),fresh.candidate(),
                fresh.artifacts(),fresh.sourceFingerprint(),fresh.workspaceIdentity(),diffs,assessment.risk(),assessment.reason(),at);
        Candidate persisted=state.followUps.persistPrepared(candidate.capsuleId(),candidate.version(),capsule,prepared,at);
        return persisted.state()==CandidateState.READY_TO_ADMIT?admit(persisted,at)
                :new Outcome(persisted,null,persisted.state().name());
    }

    private Outcome admit(Candidate candidate,Instant at)throws SQLException{
        InstructionManifestRuntime.PreparedChildManifest current=manifests.prepareChild(maxInstructionChars,at);
        String manifestId="manifest-"+candidate.capsuleId().value();
        R1bRuntimeContract contract=R1bRuntimeContract.capture(config,runtimeManifest,manifestId,
                current.candidate().canonicalHash());
        String verification=config.isVerifyEnabled()?config.effectiveVerificationCommand():"";
        FollowUpStore.ChildRuntimeSpec runtime=new FollowUpStore.ChildRuntimeSpec(candidate.instruction(),
                config.effectiveProvider(),config.model(),config.approvalMode(),config.effectiveMaxIterations(),
                new PersistenceSanitizer().sanitizeAndTruncate(verification,config.effectiveMaxVerificationOutputBytes()),
                config.effectiveMaxRepairAttempts(),runtimeManifest.skillsSha256(),runtimeManifest.hooksSha256(),contract.toJson(),
                PlanApplicability.PLAN_DISABLED);
        AdmissionResult result=state.followUps.admitChild(candidate.capsuleId(),candidate.version(),current.sourceFingerprint(),runtime,at);
        if(result instanceof Rejected rejected){
            if("MANIFEST_CANDIDATE_STALE".equals(rejected.code())){
                Candidate terminal=state.followUps.findCandidate(candidate.capsuleId()).orElse(candidate);
                if(terminal.state()!=CandidateState.FAILED)
                    return new Outcome(terminal,null,terminal.state().name());
                ParentAnchor parent=state.followUps.currentEligibleParent(candidate.threadId())
                        .orElseThrow(()->new FollowUpException("FOLLOW_UP_NOT_ELIGIBLE"));
                if(!parent.taskId().equals(candidate.parentTaskId())
                        ||parent.headVersion()!=candidate.expectedHeadVersion())
                    return new Outcome(terminal,null,"MANIFEST_CANDIDATE_STALE");
                IntentClassification continuation=new IntentClassification(Intent.CONTINUE_CURRENT_THREAD,
                        FollowUpClassifier.VERSION,"STALE_MANIFEST_REBUILD");
                Candidate rebuilt=state.followUps.createCandidate(parent,candidate.instruction(),candidate.instructionHash(),
                        candidate.provenance(),candidate.inputOwner(),candidate.inputSessionId(),continuation,at);
                return prepareAndMaybeAdmit(rebuilt,parent,at);
            }
            return new Outcome(state.followUps.findCandidate(candidate.capsuleId()).orElse(candidate),null,rejected.code());
        }
        Admitted admitted=(Admitted)result;
        return new Outcome(state.followUps.findCandidate(candidate.capsuleId()).orElse(candidate),admitted.child(),
                admitted.existing()?"ADMITTED_EXISTING":"ADMITTED");
    }

    public record Outcome(Candidate candidate,ChildAuthority child,String code){ }
    public static final class FollowUpException extends RuntimeException{public FollowUpException(String code){super(code);}}
}
