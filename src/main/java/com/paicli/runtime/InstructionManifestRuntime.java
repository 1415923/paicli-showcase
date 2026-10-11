package com.paicli.runtime;

import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.state.InstructionManifest;
import com.paicli.state.InstructionManifestCandidate;
import com.paicli.state.CodingRunStore;
import com.paicli.state.ProtectedInstructionArtifact;
import com.paicli.state.R1aDurableModels.DurableRun;
import com.paicli.state.R1aDurableModels.DurableTask;
import com.paicli.state.R1aDurableModels.TaskContextGeneration;
import com.paicli.state.R1aDurableModels.TaskContextPointer;
import com.paicli.state.R1aDurableModels.WorkThread;
import com.paicli.state.R1aStoreResults.ArtifactReadResult;
import com.paicli.state.RootAdmissionTransaction;
import com.paicli.state.StructuredPlanModels.PlanApplicability;
import com.paicli.state.StateStore;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Root Manifest admission and original-artifact recovery boundary for R1B-A. */
public final class InstructionManifestRuntime {
    private PlanRuntimeMode planRuntimeMode=PlanRuntimeMode.DEFAULT_ADVISORY;
    public static final int SCHEMA_VERSION = 1;
    public static final String RESOLUTION_POLICY_VERSION = "root-paicli-agents-v1";
    private static final int MAX_RUNTIME_GOAL_BYTES = 64 * 1024;
    private static final List<String> SOURCES = List.of("PAICLI.md","AGENTS.md");
    private final StateStore stateStore;
    private final Path workspace;
    private final PersistenceSanitizer sanitizer;

    public InstructionManifestRuntime(StateStore stateStore,Path workspace){
        this.stateStore=Objects.requireNonNull(stateStore);
        this.workspace=Objects.requireNonNull(workspace).toAbsolutePath().normalize();
        this.sanitizer=new PersistenceSanitizer();
    }

    /** Package-private seam for the retained v1.3 Strict contract tests. */
    InstructionManifestRuntime strictPlanAuthorityForTesting(){
        planRuntimeMode=PlanRuntimeMode.STRICT_INTERNAL;
        return this;
    }

    public RootAdmission admitRoot(CanonicalId taskId,String goal,int maxInstructionChars,
            com.paicli.config.Config config,RuntimeManifest runtimeManifest,Instant at)
            throws java.sql.SQLException{
        requireTask(taskId);Objects.requireNonNull(config);Objects.requireNonNull(runtimeManifest);
        Objects.requireNonNull(at);
        com.paicli.diagnostic.StageTrace.event(
                "MANIFEST_BUILD_START", false,
                "manifest_kind", "ROOT_INSTRUCTION");
        Discovery discovery;
        try {
            discovery=discover(maxInstructionChars,true,false);
            discovery.candidate().validateForCommit();
            com.paicli.diagnostic.StageTrace.event(
                    "MANIFEST_BUILD_END", false,
                    "manifest_kind", "ROOT_INSTRUCTION",
                    "outcome", "COMPLETED",
                    "entry_count", discovery.candidate().entries().size());
        } catch (RuntimeException error) {
            com.paicli.diagnostic.StageTrace.event(
                    "MANIFEST_BUILD_END", false,
                    "manifest_kind", "ROOT_INSTRUCTION",
                    "outcome", "ERROR",
                    "error_type", error.getClass().getName());
            throw error;
        }

        CanonicalId threadId=CanonicalId.newId(EntityFamily.THREAD);
        CanonicalId runId=CanonicalId.newId(EntityFamily.RUN);
        CanonicalId generationId=CanonicalId.newId(EntityFamily.GENERATION);
        WorkThread thread=new WorkThread(threadId,workspaceIdentity(),safeGoal(goal),"OPEN",at,at);
        DurableTask task=new DurableTask(taskId,threadId,null,safeGoal(goal),"CREATED",
                null,null,at,null);
        String manifestId="manifest-"+java.util.UUID.randomUUID();
        String manifestHash=discovery.candidate().canonicalHash();
        R1bRuntimeContract contract=R1bRuntimeContract.capture(
                config,runtimeManifest,manifestId,manifestHash);
        DurableRun initial=new DurableRun(runId,taskId,threadId,"INITIAL",null,
                manifestId,manifestHash,at,null);
        TaskContextGeneration generation=new TaskContextGeneration(generationId,taskId,null,runId,
                manifestId,manifestHash,"COMMITTED",contract.toJson(),at,at);
        TaskContextPointer pointer=new TaskContextPointer(taskId,generationId,runId,0);
        String runtimeGoal=sanitizer.sanitizeAndTruncate(goal,MAX_RUNTIME_GOAL_BYTES);
        String verification=config.isVerifyEnabled()?config.effectiveVerificationCommand():"";
        String persistedVerification=sanitizer.sanitizeAndTruncate(verification,
                config.effectiveMaxVerificationOutputBytes());
        var runtimeTask=new RootAdmissionTransaction.RuntimeTask(taskId.value(),runtimeGoal,
                config.effectiveProvider(),config.model(),config.approvalMode(),
                config.effectiveMaxIterations(),"RUNNING");
        String initialPhase=config.isPlanEnabled()
                ?CodingRunStore.PHASE_PLANNING:CodingRunStore.PHASE_EXECUTING;
        var runtimeRun=new RootAdmissionTransaction.RuntimeRun(runId.value(),taskId.value(),
                "INITIAL",initialPhase,persistedVerification,config.effectiveMaxRepairAttempts(),0,
                runtimeManifest.skillsSha256(),runtimeManifest.hooksSha256());
        com.paicli.diagnostic.StageTrace.event(
                "DURABLE_ADMISSION_START", true,
                "task_id", taskId.value(),
                "thread_id", threadId.value(),
                "run_id", runId.value());
        RootAdmissionTransaction.Result persisted;
        try {
            persisted=stateStore.r1aDurable
                    .admitRootTaskAtomically(new RootAdmissionTransaction.Request(thread,task,
                            manifestId,discovery.candidate(),discovery.artifacts(),initial,
                            generation,pointer,runtimeTask,runtimeRun,
                            config.isPlanEnabled()
                                    &&planRuntimeMode==PlanRuntimeMode.STRICT_INTERNAL
                                    ?PlanApplicability.PLAN_REQUIRED
                                    :PlanApplicability.PLAN_DISABLED,at));
        } catch (RuntimeException error) {
            com.paicli.diagnostic.StageTrace.event(
                    "DURABLE_ADMISSION_END", false,
                    "outcome", "ERROR",
                    "task_id", taskId.value(),
                    "error_type", error.getClass().getName());
            throw error;
        }
        if(persisted instanceof RootAdmissionTransaction.Rejected rejected) {
            com.paicli.diagnostic.StageTrace.event(
                    "DURABLE_ADMISSION_END", false,
                    "outcome", "REJECTED",
                    "task_id", taskId.value(),
                    "reason", rejected.reason());
            throw new AdmissionException("ROOT_ADMISSION_"+rejected.reason(),
                    discovery.candidate());
        }
        RootAdmissionTransaction.Success success=(RootAdmissionTransaction.Success)persisted;
        com.paicli.diagnostic.StageTrace.event(
                "DURABLE_ADMISSION_END", false,
                "outcome", "COMPLETED",
                "task_id", taskId.value(),
                "thread_id", threadId.value(),
                "run_id", success.initialRun().id().value());
        FrozenInstructionContext frozen=loadFrozen(taskId);
        return new RootAdmission(threadId,taskId,success.initialRun(),success.generation(),
                success.pointer(),success.runtimeTask(),success.runtimeRun(),frozen,contract);
    }

    public FrozenInstructionContext loadFrozen(CanonicalId taskId)
            throws java.sql.SQLException{
        requireTask(taskId);
        InstructionManifest manifest=stateStore.r1aDurable.findManifestByTask(taskId)
                .orElseThrow(()->new RecoveryMaterialException(
                        RecoveryMaterialStatus.ARTIFACT_UNAVAILABLE,"Manifest unavailable"));
        StringBuilder prompt=new StringBuilder();
        Map<String,SourceState> sources=new LinkedHashMap<>();
        for(InstructionManifest.InstructionEntry entry:manifest.entries()){
            if(entry.status()==InstructionManifestCandidate.Status.APPLIED){
                ArtifactReadResult read=stateStore.r1aDurable
                        .inspectInlineArtifact(entry.artifactId());
                if(read instanceof ArtifactReadResult.Unavailable unavailable){
                    RecoveryMaterialStatus status=unavailable.reason()
                            ==ArtifactReadResult.Reason.INTEGRITY_FAILURE
                            ?RecoveryMaterialStatus.ARTIFACT_INTEGRITY_FAILURE
                            :RecoveryMaterialStatus.ARTIFACT_UNAVAILABLE;
                    throw new RecoveryMaterialException(status,
                            "Instruction artifact unavailable: "+entry.id());
                }
                ProtectedInstructionArtifact artifact=
                        ((ArtifactReadResult.Available)read).artifact();
                if(!artifact.verifyIntegrity()
                        ||!artifact.contentHash().equals(entry.contentHash())){
                    throw new RecoveryMaterialException(
                            RecoveryMaterialStatus.ARTIFACT_INTEGRITY_FAILURE,
                            "Instruction artifact integrity failure: "+entry.id());
                }
                String content=decode(artifact.protectedPayload(),entry.sourcePath());
                if(!prompt.isEmpty())prompt.append(System.lineSeparator())
                        .append(System.lineSeparator());
                prompt.append("--- ").append(entry.sourcePath()).append(" ---")
                        .append(System.lineSeparator()).append(content);
                sources.put(entry.sourcePath(),new SourceState(entry.sourcePath(),true,
                        entry.appliedOrder(),entry.scope(),entry.contentHash(),null));
            }else{
                sources.put(entry.sourcePath(),new SourceState(entry.sourcePath(),false,null,
                        entry.scope(),null,entry.exclusionReason()));
            }
        }
        return new FrozenInstructionContext(manifest,prompt.toString(),sources);
    }

    public DriftResult detectDrift(CanonicalId taskId,int maxInstructionChars)
            throws java.sql.SQLException{
        FrozenInstructionContext frozen;
        try{frozen=loadFrozen(taskId);}
        catch(RecoveryMaterialException e){return new DriftResult(e.status(),List.of(e.getMessage()));}
        if(frozen.manifest().schemaVersion()!=SCHEMA_VERSION
                ||!RESOLUTION_POLICY_VERSION.equals(
                frozen.manifest().resolutionPolicyVersion())){
            return new DriftResult(RecoveryMaterialStatus.INSTRUCTION_DRIFT_INCOMPATIBLE,
                    List.of("RESOLUTION_POLICY_OR_SCHEMA_CHANGED"));
        }
        Discovery current;
        try{current=discover(maxInstructionChars,false,false);}
        catch(AdmissionException e){
            return new DriftResult(RecoveryMaterialStatus.INSTRUCTION_DRIFT_INCOMPATIBLE,
                    List.of(e.getMessage()));
        }
        Map<String,SourceState> now=current.sources();
        List<String> incompatible=new ArrayList<>();
        List<String> informational=new ArrayList<>();
        for(String source:SOURCES){
            SourceState before=frozen.sources().get(source);
            SourceState after=now.get(source);
            if(before==null||after==null){incompatible.add("SOURCE_SET_CHANGED:"+source);continue;}
            if(before.applied()!=after.applied()){
                incompatible.add((after.applied()?"SOURCE_ADDED:":"SOURCE_REMOVED:")+source);
                continue;
            }
            if(before.applied()){
                if(!Objects.equals(before.contentHash(),after.contentHash()))
                    incompatible.add("CONTENT_HASH_CHANGED:"+source);
                if(!Objects.equals(before.appliedOrder(),after.appliedOrder()))
                    incompatible.add("APPLIED_ORDER_CHANGED:"+source);
                if(!Objects.equals(before.scope(),after.scope()))
                    incompatible.add("SCOPE_CHANGED:"+source);
            }else if(!Objects.equals(before.exclusionReason(),after.exclusionReason())){
                informational.add("OPTIONAL_SOURCE_STATE_CHANGED:"+source);
            }
        }
        if(!incompatible.isEmpty())return new DriftResult(
                RecoveryMaterialStatus.INSTRUCTION_DRIFT_INCOMPATIBLE,
                List.copyOf(incompatible));
        if(!informational.isEmpty())return new DriftResult(
                RecoveryMaterialStatus.INSTRUCTION_DRIFT_INFORMATIONAL,
                List.copyOf(informational));
        return new DriftResult(RecoveryMaterialStatus.NO_DRIFT,List.of());
    }

    /** Prepares fresh, deterministic Child instruction material without granting authority. */
    public PreparedChildManifest prepareChild(int maxInstructionChars,Instant at){
        Objects.requireNonNull(at);
        Discovery discovery=discover(maxInstructionChars,true,true);
        discovery.candidate().validateForCommit();
        String fingerprint=sha256((workspaceIdentity()+"\n"+discovery.candidate().canonicalHash())
                .getBytes(StandardCharsets.UTF_8));
        return new PreparedChildManifest(discovery.candidate(),discovery.artifacts(),fingerprint,
                workspaceIdentity(),at);
    }

    public boolean childSourcesStillMatch(PreparedChildManifest prepared,int maxInstructionChars){
        PreparedChildManifest current=prepareChild(maxInstructionChars,prepared.preparedAt());
        return current.sourceFingerprint().equals(prepared.sourceFingerprint())
                &&current.candidate().canonicalHash().equals(prepared.candidate().canonicalHash());
    }

    private Discovery discover(int maxInstructionChars,boolean buildArtifacts,boolean stableIds){
        if(maxInstructionChars<0)throw new IllegalArgumentException(
                "maxInstructionChars must not be negative");
        List<InstructionManifestCandidate.Entry> entries=new ArrayList<>();
        List<ProtectedInstructionArtifact> artifacts=new ArrayList<>();
        Map<String,SourceState> sources=new LinkedHashMap<>();
        int order=0;int total=0;Instant observed=Instant.now();
        for(String source:SOURCES){
            Path file=workspace.resolve(source).normalize();
            if(!file.startsWith(workspace))throw failure(
                    "SCOPE_RESOLUTION_FAILED:"+source,entries);
            if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS)){
                String reason="OPTIONAL_SOURCE_ABSENT";
                entries.add(ignored(source,reason,stableIds));sources.put(source,
                        new SourceState(source,false,null,"WORKSPACE",null,reason));continue;
            }
            if(Files.isSymbolicLink(file)
                    ||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))
                throw failure("READ_FAILED:"+source,entries);
            byte[] bytes;
            try{bytes=Files.readAllBytes(file);}
            catch(IOException e){throw failure("READ_FAILED:"+source,entries);}
            String content;
            try{content=decode(bytes,source);}
            catch(RecoveryMaterialException e){throw failure("PARSE_FAILED:"+source,entries);}
            if(content.isBlank()){
                String reason="OPTIONAL_SOURCE_EMPTY";
                entries.add(ignored(source,reason,stableIds));sources.put(source,
                        new SourceState(source,false,null,"WORKSPACE",null,reason));continue;
            }
            total+=content.length();
            if(maxInstructionChars>0&&total>maxInstructionChars)
                throw failure("INSTRUCTION_LIMIT_EXCEEDED:"+source,entries);
            String hash=sha256(bytes);
            String artifactId=stableIds?"instruction-"+hash.substring(0,40)
                    :"artifact-"+java.util.UUID.randomUUID();
            if(buildArtifacts)artifacts.add(ProtectedInstructionArtifact.inline(
                    artifactId,bytes,"instruction-utf8-v1","TASK_AUTHORITY",observed));
            entries.add(new InstructionManifestCandidate.Entry(
                    stableIds?"child-entry-"+sha256((source+"\n"+hash).getBytes(StandardCharsets.UTF_8)).substring(0,40)
                            :"entry-"+java.util.UUID.randomUUID(),"PROJECT_FILE",source,"WORKSPACE",
                    InstructionManifestCandidate.Status.APPLIED,order,hash,artifactId,
                    "UTF8_MARKDOWN_ROOT_SCOPE_V1",null,null));
            sources.put(source,new SourceState(source,true,order,"WORKSPACE",hash,null));
            order++;
        }
        InstructionManifestCandidate candidate=new InstructionManifestCandidate(
                SCHEMA_VERSION,RESOLUTION_POLICY_VERSION,entries);
        return new Discovery(candidate,List.copyOf(artifacts),Map.copyOf(sources));
    }

    private static InstructionManifestCandidate.Entry ignored(String source,String reason,
            boolean stableId){
        String id=stableId?"child-entry-"+sha256((source+"\n"+reason)
                .getBytes(StandardCharsets.UTF_8)).substring(0,40)
                :"entry-"+java.util.UUID.randomUUID();
        return new InstructionManifestCandidate.Entry(id,
                "PROJECT_FILE",source,"WORKSPACE",
                InstructionManifestCandidate.Status.IGNORED,null,null,null,
                "OPTIONAL_SOURCE_V1",reason,null);
    }
    private AdmissionException failure(String reason,
            List<InstructionManifestCandidate.Entry> prior){
        List<InstructionManifestCandidate.Entry> failed=new ArrayList<>(prior);
        String source=reason.substring(reason.indexOf(':')+1);
        failed.add(new InstructionManifestCandidate.Entry("entry-"+java.util.UUID.randomUUID(),
                "PROJECT_FILE",source,"WORKSPACE",
                InstructionManifestCandidate.Status.FAILED,null,null,null,null,null,reason));
        return new AdmissionException(reason,new InstructionManifestCandidate(
                SCHEMA_VERSION,RESOLUTION_POLICY_VERSION,failed));
    }
    private static String decode(byte[] bytes,String source){
        try{return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();}
        catch(CharacterCodingException e){throw new RecoveryMaterialException(
                RecoveryMaterialStatus.ARTIFACT_INTEGRITY_FAILURE,
                "Instruction is not valid UTF-8: "+source);}
    }
    private static String sha256(byte[] bytes){
        try{return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(Exception e){throw new IllegalStateException("SHA-256 unavailable",e);}
    }
    private String workspaceIdentity(){
        return "workspace-sha256-"+sha256(
                workspace.toString().getBytes(StandardCharsets.UTF_8)).substring(0,24);
    }
    private String safeGoal(String goal){
        String safe=sanitizer.sanitizeAndTruncate(goal,4096);
        if(safe==null||safe.isBlank())throw new IllegalArgumentException("goal is required");
        return safe;
    }
    private static void requireTask(CanonicalId id){
        if(id==null||id.family()!=EntityFamily.TASK)
            throw new IllegalArgumentException("Canonical Task ID required");
    }
    public record RootAdmission(CanonicalId threadId,CanonicalId taskId,DurableRun initialRun,
                                TaskContextGeneration generation,
                                TaskContextPointer pointer,
                                RootAdmissionTransaction.RuntimeTask runtimeTask,
                                RootAdmissionTransaction.RuntimeRun runtimeRun,
                                FrozenInstructionContext instructions,
                                R1bRuntimeContract runtimeContract){ }
    public record PreparedChildManifest(InstructionManifestCandidate candidate,
            List<ProtectedInstructionArtifact> artifacts,String sourceFingerprint,
            String workspaceIdentity,Instant preparedAt){
        public PreparedChildManifest{artifacts=List.copyOf(artifacts);}
    }
    public static final class FrozenInstructionContext{
        private final InstructionManifest manifest;private final String prompt;
        private final Map<String,SourceState> sources;
        FrozenInstructionContext(InstructionManifest manifest,String prompt,
                Map<String,SourceState> sources){
            this.manifest=manifest;this.prompt=prompt;this.sources=Map.copyOf(sources);
        }
        public InstructionManifest manifest(){return manifest;}
        public String prompt(){return prompt;}
        public Map<String,SourceState> sources(){return sources;}
        @Override public String toString(){return "FrozenInstructionContext[manifest="
                +manifest.id()+", content=REDACTED]";}
    }
    public record SourceState(String sourcePath,boolean applied,Integer appliedOrder,String scope,
                              String contentHash,String exclusionReason){ }
    private record Discovery(InstructionManifestCandidate candidate,
                             List<ProtectedInstructionArtifact> artifacts,
                             Map<String,SourceState> sources){ }
    public record DriftResult(RecoveryMaterialStatus status,List<String> reasons){
        public DriftResult{reasons=List.copyOf(reasons);}
    }
    public enum RecoveryMaterialStatus{
        NO_DRIFT,INSTRUCTION_DRIFT_INFORMATIONAL,INSTRUCTION_DRIFT_INCOMPATIBLE,
        ARTIFACT_UNAVAILABLE,ARTIFACT_INTEGRITY_FAILURE
    }
    public static final class AdmissionException extends RuntimeException{
        private final InstructionManifestCandidate candidate;
        AdmissionException(String message,InstructionManifestCandidate candidate){
            super(message);this.candidate=candidate;
        }
        public InstructionManifestCandidate candidate(){return candidate;}
    }
    public static final class RecoveryMaterialException extends RuntimeException{
        private final RecoveryMaterialStatus status;
        RecoveryMaterialException(RecoveryMaterialStatus status,String message){
            super(message);this.status=status;
        }
        public RecoveryMaterialStatus status(){return status;}
    }
}
